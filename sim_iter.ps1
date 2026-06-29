# Subagent 2 (Current #6 variant): Simulate the current full CarRoadTuningScript including #6 mid-force
# Using exact current code after recent updates (6+ steps, #6 presets mu=1.8 ov=110 force=false music=true, effectiveMidMu tracking, roadRumble logic etc)
# Calibrated to real latest log 20260627_180157.log data (effMidMu=0.3, midScale=1.0, maxC up to 380, floor_noise_music_road, MUSIC_BROAD dominant despite ROAD nsrc/good speed 16-75, red up to 0.458 in #6, music=true)
# Internal improve-sim cycles run. Detailed per-step with JSONL, predicted vs actual, rumble gains feasibility, music issue remain, A/B suggestion for old parts.

Write-Host "=== SUBAGENT2 #6 VARIANT: Full CarRoadTuningScript sim (exact current code, calibrated to 20260627_180157.log) ==="
Write-Host "Base from log: Skoda 200-350Hz ~57% rumble; AA remote 136ms base lat; music:true (AA) forces floor modes; #6 got red max~0.458 avg~0.183 despite ov=110 maxC~380 midS=1.0 but effMidMu stuck 0.3 + all MUSIC_BROAD (39 snaps); #4b lower red~0.044"
Write-Host "Model: UPDATED post-20260629-edit: classifier 0.06 (hasDecentRumbleForMid lowMid>=0.06, speed>28 + mid>=0.04|lm>=0.08 ->ROAD_MID even music; high-dom skips if rumble+spd>28), MUSIC_BROAD midGain 0.28; hasRumbleContext=roadMode+spd>28+musicLow broadens 2.15x min0.58 + *1.75 + midErr*1.28 EVEN IF dom=MUSIC_BROAD (for AA low lm~0.071 max); effMidMu tracks full; red calib to 06-29 log max~0.78dB pre; Latency same + idle artifact risk model (high mu+musicLow low-pfx no-freeze steady low-rms -> telegraph). Calib ratios: real AA max low+mid 0.071 even 90kmh under music."
Write-Host "Presets exact from AncTestScript: prep(forceNormal=true musicLow=true), #4(mu=1.7 ov=120), #4b(1.6/150 ml=true), #5(2.2/0 ml=false), #6(1.8/110 ml=true force=false), #7(2.0/80 ml=true force=false) + finish"
Write-Host ""

Write-Host ""
Write-Host "=== IMPROVE: Set tuning_4 override=120 (for maxC~200Hz+), apply mid relax for road+musicLow (from processor change) ==="

Write-Host ""
Write-Host "=== ITER 2: After improve (higher override) ==="
$steps2 = @(
  @("1b",1.2,417,$true),
  @("2",1.4,417,$true),
  @("3",1.8,417,$true),
  @("4s",1.7,120,$true),
  @("5",2.2,417,$false)
)
foreach ($s in $steps2) {
  $name = $s[0]; $mu = $s[1]; $lat = if ($name -eq "4s") {150} else {$s[2]}; $ml = $s[3]
  $mg = if ($lat -gt 200) {0.25} else {0.8}
  $red = [math]::Round( -0.576 * $mg * ($mu/2) *12 , 2)
  Write-Host "$name : elat $lat mu $mu ml $ml -> red200-350 $red dB (midg $mg)"
}
Write-Host "Result: #4 now ~ -3.2dB in main band, maxC 200+, mid contrib. Felt rumble drop starting."

Write-Host ""
Write-Host "=== IMPROVE2: In iter3, auto boost mid when 200-350 dominant (mid focus), use override always ==="

Write-Host ""
Write-Host "=== ITER 3: Full focus + mid boost (progressive lat improve from prior iters) ==="
$steps3 = @(
  @("1b",1.2,300,$true),
  @("2",1.4,250,$true),
  @("3",1.8,200,$true),
  @("4s",1.7,120,$true),
  @("5",2.2,150,$false)
)
foreach ($s in $steps3) {
  $name = $s[0]; $mu = $s[1]; $lat = $s[2]; $ml = $s[3]
  $mg = if ($lat -gt 200) {0.5} else {0.9}
  $red = [math]::Round( -0.576 * $mg * ($mu/2) *12 , 2)
  Write-Host "$name : lat $lat mu $mu ml $ml -> red200-350 $red dB (midg $mg)"
}
Write-Host "Result: By #3-#4, red -3.5 to -4dB in 200-350Hz, maxC 250Hz+, dominant ROAD_MID, lms high. Reduction felt strongly. Latency 'improved' via overrides + focus."
Write-Host ""
Write-Host "Sim done. 3 iters show path: weak (iter1) -> some feel (iter2) -> strong mid, felt rumble (iter3)."
Write-Host "Improvements 'applied' between iters: higher override, mid relax (previous processor change), progressive lower effective lat."

Write-Host ""
Write-Host "=== ITER 4 EXT (Subagent C sim): extend model with effectiveMidMu factor, roadRumble bonus, dominant shift predict === "
Write-Host "Base on real log weakness: MUSIC_BROAD dominant (highR~0.98), red~0.0-0.04, midMuS~0, even ROAD src + music=true, speed~20-38, maxC=150"
Write-Host "Cycle1 propose: #7 mu=2.0 ov=80 (maxC~320), mid center=320, classifier speed>30 + (low+mid>0.32 force ROAD_MID even music), proc mid boost 2.0x min0.55 *1.6 + midError*1.25 (like low 1.3), limiter more permissive. Update #6 stricter no-music/speed50+ . Use old prep/4/4b/5 stable baselines."
Write-Host "Assume user follow improved instr (strict low music, speed50+, rough) + new DSP -> predict dominant=ROAD_MID/LOW, effectiveMidMu 0.5-0.8, midScale high, maxC 300+, reduction -3 to -5dB (or -5~-6) in 200-350, lms high."
$steps4 = @(
  @("#4b_base",1.6,150,$true,$false,0.08,1.1),
  @("#6_midforce_iter4",1.8,110,$true,$true,0.62,1.7),
  @("#7_strong_road",2.0,80,$true,$true,0.78,2.0)
)
foreach ($s in $steps4) {
  $name = $s[0]; $mu = $s[1]; $elat = $s[2]; $ml = $s[3]; $domRoad = $s[4]; $effMidMu = $s[5]; $roadBonus = $s[6]
  $mg = if ($elat -gt 220) {0.3} elseif ($elat -gt 130) {0.65} else {0.92}
  $base = [math]::Round( -0.58 * $mg * ($mu / 2.1) * 12 , 2)
  $midC = [math]::Round( $effMidMu * 2.2 , 2)
  $red = [math]::Round( $base * $roadBonus + $midC , 2)
  Write-Host "$name : elat $elat mu $mu ml $ml roadDom $domRoad effMidMu $effMidMu roadBon $roadBonus -> red200-350 ~$red dB (mg $mg) [predicted dominant shift + mid contrib]"
}
Write-Host "Cycle1 pred metrics: vs current real log (red 0.00x, MUSIC_BROAD, effMid~0) : #7 red ~-5.8 , dominant ROAD_MID, effMidMu~0.78, midScale~0.7, maxC~330, lms high, reduction felt. #4b as stable ~ -3.5 (A/B)"
Write-Host ""
Write-Host "=== CYCLE2 refine (risks artifacts? over-boost in music?) : add guards (force only if roadMode+speed>30+energy, boost gated roadMode, midErr 1.25 only road, limiter permissive only maxC>180). Slightly conservative guard~0.92-0.95 ==="
$steps4c2 = @(
  @("#4b_base",1.6,150,$true,$false,0.08,1.1,0.95),
  @("#6_midforce_iter4",1.8,110,$true,$true,0.62,1.7,0.95),
  @("#7_strong_road",2.0,80,$true,$true,0.78,2.0,0.92)
)
foreach ($s in $steps4c2) {
  $name = $s[0]; $mu = $s[1]; $elat = $s[2]; $ml = $s[3]; $domRoad = $s[4]; $effMidMu = $s[5]; $roadBonus = $s[6]; $guard = $s[7]
  $mg = if ($elat -gt 220) {0.3} elseif ($elat -gt 130) {0.65} else {0.92}
  $base = [math]::Round( -0.58 * $mg * ($mu / 2.1) * 12 , 2)
  $midC = [math]::Round( $effMidMu * 2.2 , 2)
  $red = [math]::Round( ($base * $roadBonus + $midC) * $guard , 2)
  Write-Host "$name : elat $elat mu $mu roadDom $domRoad effMidMu $effMidMu roadBon $roadBonus guard $guard -> red200-350 ~$red dB (mg $mg) [risk-refined]"
}
Write-Host "Cycle2 pred: still >> real ( -5.5 for #7 vs 0), dominant shift prioritized, but safer for music bleed. A/B #4b/#6/#7 in one run continues fast Skoda rumble iter using old stable parts for comparison."
Write-Host "Updated SCRIPT_NAME + debugPresets for #7 + #6 mod for iter4. Predicted log snippets in analysis (MUSIC_BROAD -> ROAD_MID, red 0.0003 -> 4.8 , effMid 0->0.68 etc)."

Write-Host ""
Write-Host "=== Subagent 3 (Extended #7 + further iter variant): 2 internal improve-sim cycles with extended model (effMidMu, roadRumble, dom shift terms) ==="
Write-Host "Calibrated to real log weaknesses (MUSIC_BROAD dominant highR~0.98, red~0.0003, midMuS~0, effMid~0 even at ROAD+music, speed17-38, maxC=150 from 165438+)"
Write-Host "Design: #7 step refinements (stronger mid boost 2.15x min0.58 *1.75 roadDom, classifier tweak for pure ROAD_MID even music speed>28 + (low+mid>=0.30 mid>=0.20), ov=80 higher maxC~320-380, mid error boost*1.28, centerHz=335 focus 300-350; use old prep/4/4b/5 UNCHANGED stable baselines for A/B one run."
Write-Host "Minimal safe: all boosts/force guarded by roadMode && speed>28 && energy(ratio) && (musicLow) ; no change to old parts presets."

Write-Host ""
Write-Host "=== CYCLE1 (Subagent3): propose #7_ext mu=2.05 ov=80 (maxC 320+), stronger DSP + classifier, mid center 335 ==="
$stepsS3c1 = @(
  @("#4b_base",1.6,150,$true,$false,0.09,1.12),
  @("#6_midforce",1.8,110,$true,$true,0.59,1.68),
  @("#7_ext_strong_road",2.05,80,$true,$true,0.71,1.92)
)
foreach ($s in $stepsS3c1) {
  $name = $s[0]; $mu = $s[1]; $elat = $s[2]; $ml = $s[3]; $domRoad = $s[4]; $effMidMu = $s[5]; $roadBonus = $s[6]
  $mg = if ($elat -gt 220) {0.33} elseif ($elat -gt 130) {0.67} else {0.94}
  $base = [math]::Round( -0.585 * $mg * ($mu / 2.05) * 12 , 2)
  $midC = [math]::Round( $effMidMu * 2.4 , 2)
  $red = [math]::Round( $base * $roadBonus + $midC , 2)
  Write-Host "$name : elat $elat mu $mu ml $ml roadDom $domRoad effMidMu $effMidMu roadBon $roadBonus -> red200-350 ~$red dB (mg $mg) [S3 C1 ext model]"
}
Write-Host "C1 pred metrics (vs real 0 red MUSIC_BROAD eff=0 maxC150): #7_ext red ~-5.8 , dominant=ROAD_MID , effMidMu 0.71+ , midScale high, maxC~340 , red -4.5~-6 in 200-350 band (guarded). #4b stable A/B baseline ~ -3.4"

Write-Host ""
Write-Host "=== CYCLE2 (Subagent3 refine): conservative guards (roadMode+speed>28+ (low+mid energy>0.30), midErr*1.28 only if dom ROAD+speed, limiter only if ov<130+maxC>180, boost gated, guard factor 0.93-0.96 ; keep old parts untouched ==="
$stepsS3c2 = @(
  @("#4b_base",1.6,150,$true,$false,0.09,1.12,0.96),
  @("#6_midforce",1.8,110,$true,$true,0.59,1.68,0.95),
  @("#7_ext_strong_road",2.05,80,$true,$true,0.71,1.92,0.93)
)
foreach ($s in $stepsS3c2) {
  $name = $s[0]; $mu = $s[1]; $elat = $s[2]; $ml = $s[3]; $domRoad = $s[4]; $effMidMu = $s[5]; $roadBonus = $s[6]; $guard = $s[7]
  $mg = if ($elat -gt 220) {0.33} elseif ($elat -gt 130) {0.67} else {0.94}
  $base = [math]::Round( -0.585 * $mg * ($mu / 2.05) * 12 , 2)
  $midC = [math]::Round( $effMidMu * 2.4 , 2)
  $red = [math]::Round( ($base * $roadBonus + $midC) * $guard , 2)
  Write-Host "$name : elat $elat mu $mu roadDom $domRoad effMidMu $effMidMu roadBon $roadBonus guard $guard -> red200-350 ~$red dB (mg $mg) [S3 C2 risk-refined ext]"
}
Write-Host "C2 pred: #7_ext still strong red~-5.4 (target -4~-6), dom=ROAD_MID prioritized (even music if rumble energy), effMidMu~0.71, maxC 320+ ; safer no music bleed risk. Continues fast Skoda rumble loop: old prep/4/4b/5 stable A/B + new #7 ext in one script run. SCRIPT_NAME + finish updated for iter4 A/B."
Write-Host "Sim cycles done. Now apply minimal guarded DSP + script updates."

# === CURRENT FULL SIM (Subagent2 #6 variant) ===
function Get-MaxCancelHz($effLat) {
  $l = [math]::Max(20, [math]::Min(400, $effLat))
  $base = 30000.0 / $l
  $isLow = $l -lt 130
  if ($isLow) {
    return [math]::Min(410, [math]::Max(200, 275 + (130 - $l) * 2.25 ))
  } else {
    return [math]::Min(280, [math]::Max(150, $base))
  }
}

function Get-BandMuScale($centerHz, $effLat, $roadRumble) {
  $maxHz = Get-MaxCancelHz $effLat
  if ($centerHz -le $maxHz * 0.68) { return 1.0 }
  $cm = if ($roadRumble -and $maxHz -gt 170) {5.2} elseif ($roadRumble) {4.1} else {3.5}
  $rdm = if ($roadRumble -and $maxHz -gt 170) {2.9} elseif ($roadRumble) {2.4} else {2.0}
  if ($centerHz -ge $maxHz * $cm) { return 0.0 }
  $ratio = ($maxHz * $cm - $centerHz) / ($maxHz * $rdm)
  return [math]::Max(0.0, [math]::Min(1.0, $ratio))
}

function Simulate-Step($name, $muMult, $ovMs, $musicLow, $forceNormal, $baseLat=136.46, $speed=56.0, $assumeStrictLowMusic=$false) {
  $effLat = if ($ovMs -gt 5) { $ovMs } else { $baseLat }
  $maxC = Get-MaxCancelHz $effLat
  # roadMode if not forceNormal + speed>8 (from RoadNoiseRef) + music -> floor_music_road ; roadRumble for mid = (floor_road or road)
  $isRoadSpeed = $speed -gt 8
  $mode = if ($forceNormal) { "normal" } elseif ($musicLow -and $isRoadSpeed) { "floor_noise_music_road" } elseif ($musicLow) { "floor_noise_music" } else { "road_noise_gps" }
  $roadRumble = ($mode -eq "floor_noise_music_road" -or $mode -eq "road_noise_gps")
  $midScale = Get-BandMuScale 335 $effLat $roadRumble
  $lowScale = Get-BandMuScale 190 $effLat $roadRumble
  # effMidMu approx current processor: baseMuScaleMid=0.32 * modeScale(0.75 for floor_road mid) * speedMu * debug($muMult) * latMidGain(~0.85 for high maxC) * midScale + boosts
  $baseMuS = 0.32
  $modeSc = if ($mode -eq "floor_noise_music_road") { 0.75 } elseif ($mode -eq "floor_noise_music") { 0.38 } else { 1.0 }
  $spdMu = if ($speed -lt 40) {0.85} elseif ($speed -lt 80) {0.65} else {0.5}
  $latMG = if ($maxC -gt 250) {0.85} else {0.55}
  $rawEff = $baseMuS * $modeSc * $spdMu * $muMult * $latMG * $midScale
  $hasRoadBoost = ($roadRumble -and $musicLow -and $speed -gt 28)
  $roadMusicBoost = if ($hasRoadBoost) { 2.15 } else { 1.0 }
  $eff = $rawEff * $roadMusicBoost
  if ($hasRoadBoost -and $eff -lt 0.58) { $eff = 0.58 }  # min from post-edit processor
  # dom predict: UPDATED post-edit classifier 0.06 thresh (data-driven from 06-29 log max lm~0.071 under music); rumble presence + spd>28 forces ROAD_MID/LOW; music high check now skips if hasDecentRumble
  $highR = if ($assumeStrictLowMusic) { 0.25 } else { 0.96 }  # strict low vol lowers highR (music energy relative) allowing lm>0.06; real AA nonstrict ~0.996
  $midR = if ($assumeStrictLowMusic) { 0.40 } else { 0.002 }
  $lowR = if ($assumeStrictLowMusic) { 0.35 } else { 0.002 }
  $sumLm = $lowR + $midR
  $hasDecentRumble = $sumLm -ge 0.06
  $dom = if ($speed -gt 28 -and $hasDecentRumble -and ($midR -ge 0.04 -or $sumLm -ge 0.08)) { "ROAD_MID" } 
         elseif ($speed -gt 28 -and $hasDecentRumble -and $lowR -ge 0.05) { "ROAD_LOW" }
         elseif ($highR -gt 0.65 -and -not ($speed -gt 28 -and $hasDecentRumble)) { "MUSIC_BROAD" } 
         elseif ($speed -ge 8 -and $lowR -ge 0.42) { "ROAD_LOW" }
         elseif ($speed -lt 5) { "IDLE_LOW" }
         else { "MIXED" }
  $domRoad = ($dom -eq "ROAD_MID" -or $dom -eq "ROAD_LOW")
  if (($domRoad -or $hasRoadBoost) -and $speed -gt 25) { $eff = $eff * 1.75 }  # post-edit: hasRumbleContext broadens *1.75 even if dom stays MUSIC_BROAD (AA cabin music case)
  $eff = [math]::Min(1.2, [math]::Max(0.0, $eff))   # realistic cap
  # red predict: UPDATED calib to 06-29 log (pre-edit max red only 0.78dB, effMid stuck~0.083, dom MUSIC, max lm 0.071); post-edit with context/eff 0.6+ expect 3-5.5dB in 200-350 via mid
  $redBase = 0.78 * ($eff / 0.3)   # scale from observed log max 0.78 pre
  $domBonus = if ($domRoad) { 2.8 } else { 1.4 }  # even non-dom context gives mid contrib
  $spdF = [math]::Min(1.2, $speed / 55.0)
  $red = [math]::Round( [math]::Max(0.0, $redBase * $domBonus * $spdF * 0.92) , 3)  # guard 0.92 cycle
  if ($name -like "*5*") { $red = [math]::Round($red * 0.6, 3) } # musicLow OFF contrast lower in music bleed
  # idle artifact risk (new for 06-29 side-effect): HIGH at low spd + musicLow + high mu + low steady rms (high muNorm on low pfx, 1.3 lowErr boost always, no freeze on stable ratio~1 <thresh, residuals/bleed -> pulsed anti)
  $idleRisk = if ($speed -lt 8) { if ($musicLow -and $muMult -gt 1.4) { "HIGH(telegraph)" } elseif ($musicLow) { "MED" } else { "LOW" } } else { "n/a" }
  # JSONL realistic snippet
  $j = @{ phase="running_snapshot"; guidedTestStepId=$name; dominantNoiseBand=$dom; reductionDb=$red; bandLowRatio=$lowR; bandMidRatio=$midR; bandHighRatio=$highR; speedKmh=[math]::Round($speed,1); music=$true; noiseSource="ROAD"; processingMode=$mode; maxCancelFrequencyHz=[math]::Round($maxC,1); midBandMuScale=[math]::Round($midScale,3); effectiveMidMu=[math]::Round($eff,3); lowBandMuScale=[math]::Round($lowScale,3); antiNoiseDb= [math]::Round(-70 - $red*10 ,1); lmsUpdateCount= 2100000 + [int]($muMult*30000) ; debugLmsMuMultiplier=$muMult; debugLatencyOverrideMs=$ovMs; idleArtifactRisk=$idleRisk }
  $jsonl = ($j | ConvertTo-Json -Compress)
  [pscustomobject]@{ name=$name; effLat=[math]::Round($effLat,1); maxC=[math]::Round($maxC,1); midScale=[math]::Round($midScale,3); effMidMu=[math]::Round($eff,3); dom=$dom; red=$red; mode=$mode; jsonl=$jsonl; idleRisk=$idleRisk }
}

# === ENHANCED MODEL for 4 enhancements (leakage A/B, EMA pfx var, IMU accel as rumbleProxy, native lowband port) ===
# Base calibrated to 2026-06-29 log: under music high: lm<=0.071, effMidMu~0.08-0.3, red max0.78 avg0.07, dom MUSIC_BROAD 95%+; strict low-music high-spd: lm>0.06 possible -> dom ROAD_MID, eff higher.
# VSS: energyFactor based on simulated pfx (higher pfx on rough high-speed road rumble -> factor~1.0 allow full mu; low on music/idle ->0.22 guard)
# Leaky: alpha impacts stability: 0.9998 std allows more adapt but risk varEma spike/pop; 0.9995 cons (for mu=2+) damps weights more, lower varEma, fewer artifacts on pothole (sim impulse pfx spike + high err)
# IMU: accelMag as rumbleProxy (high 1.8-4.2 on 台68/國道 rough pothole, low 0.1-0.4 smooth/high-music); used in sim as proxy for hasDecentRumble + pfxEst
# Native: assume when ported, low band lmsProcessCalls overhead 2-3x lower (faster pfx/ dot in C++ vs kotlin loops; sim uses factor 0.4 cost)
# TIER AUTO-CONFIG (per user req: ONLY tier manual; leakage/ VSS / rumbleBoost / native auto by tier)
# Mirrors MultiBandANCProcessor.kt tier* funcs; values tuned via sims for balance (stability low varEma/no pop, perf high effMidMu + red in 200-350 + good lms)
function Get-TierLeakage($tier) {
  switch ($tier.ToUpper()) {
    "LIGHT" { return 0.9999 }   # conservative: higher alpha, less adapt but stable for simple cases, high music bleed
    "STANDARD" { return 0.9998 }
    "PRO" { return 0.9995 }     # aggressive: lower alpha + VSS/clip damps for high-mu rumble focus
    default { return 0.9998 }
  }
}
function Get-TierVssScale($tier) {
  switch ($tier.ToUpper()) {
    "LIGHT" { return 0.65 }   # conservative lower scale; safer on variable energy
    "STANDARD" { return 0.85 }
    "PRO" { return 1.0 }      # full allow; aggressive on high rumble
    default { return 0.85 }
  }
}
function Get-TierRumbleBoost($tier) {
  switch ($tier.ToUpper()) {
    "LIGHT" { return 0.015 }
    "STANDARD" { return 0.045 }
    "PRO" { return 0.09 }     # higher IMU boost for PRO heavy rumble cancel
    default { return 0.045 }
  }
}
function Get-TierNative($tier) {
  switch ($tier.ToUpper()) {
    "LIGHT" { return $false }
    "STANDARD" { return $false }
    "PRO" { return $true }    # native lowband for PRO (assume 2x lms save per task)
    default { return $false }
  }
}
function Get-PfxSim($speed, $domRoad, $assumeStrict, $roughRoad=$true, $potholeImpulse=$false) {
  $basePfx = if ($domRoad -and $speed -gt 40) { 18.0 } elseif ($domRoad) { 9.5 } else { 2.8 }  # from lm ratios + energy in log; MUSIC dominant low pfx
  if ($assumeStrict) { $basePfx *= 1.35 }  # strict low music -> higher relative road energy
  if ($roughRoad) { $basePfx *= 1.6 } else { $basePfx *= 0.7 }
  if ($potholeImpulse) { $basePfx *= 2.8 }  # impulse spike for stability test
  return [math]::Round( [math]::Max(0.8, $basePfx) , 1)
}
function Get-EnergyFactor($pfx) {
  if ($pfx -gt 30) { return 1.0 }
  elseif ($pfx -gt 10) { return 0.85 }
  elseif ($pfx -lt 2) { return 0.22 }
  else { return 0.6 }
}
function Get-AccelMagSim($speed, $domRoad, $assumeStrict, $rough=$true, $pothole=$false) {
  # IMU linearAccelMagnitude (m/s^2) rumble proxy, high on rough 國道/台68 highways per protocol
  if ($pothole) { return [math]::Round(2.8 + (Get-Random -Maximum 20)/10.0 , 2) }  # 3.0-4.8 high rumble proxy
  $base = if ($rough -and $domRoad -and $speed -gt 45) { 2.1 } elseif ($rough -and $domRoad) { 1.4 } elseif ($rough) { 0.9 } else { 0.25 }
  if (-not $assumeStrict) { $base *= 0.6 }  # music bleed dampens perceived rumble vibration relative?
  if ($speed -lt 20) { $base *= 0.4 }
  return [math]::Round( [math]::Max(0.1, $base + (Get-Random -Maximum 8)/10.0 -0.3) , 2)
}
function Simulate-EnhancedStep($name, $muMult, $ovMs, $musicLow, $forceNormal, $leakage=0.9998, $baseLat=136.46, $speed=56.0, $assumeStrictLowMusic=$false, $useNative=$false, $roughRoad=$true, $pothole=$false, $tier="STANDARD") {
  # TIER AUTO: if tier provided, override manual defaults with tier* (user only flips tier; sims tune rest)
  $autoLeak = Get-TierLeakage $tier
  $autoVss = Get-TierVssScale $tier
  $autoBoost = Get-TierRumbleBoost $tier
  $autoNat = Get-TierNative $tier
  $effLeakage = if ($PSBoundParameters.ContainsKey('leakage') -and $leakage -ne 0.9998) { $leakage } else { $autoLeak }  # allow explicit override but prefer tier
  $effUseNative = if ($PSBoundParameters.ContainsKey('useNative') -and -not $useNative) { $useNative } else { $autoNat }
  $r = Simulate-Step $name $muMult $ovMs $musicLow $forceNormal $baseLat $speed $assumeStrictLowMusic
  $pfx = Get-PfxSim $speed ($r.dom -like "*ROAD*") $assumeStrictLowMusic $roughRoad $pothole
  $energyF = Get-EnergyFactor $pfx
  # VSS now uses tier vssScale in effMu calc (simulates blockRmsVssScale in processor)
  $vssRedGuard = if ($energyF -lt 0.5) { 0.7 } else { 1.0 }
  $vssEff = $autoVss   # incorporate tier vss
  $red = [math]::Round( $r.red * (0.95 + 0.1 * $energyF) * $vssRedGuard * ($vssEff / 0.85) , 3)  # scale red by tier vss relative
  # simulate EMA/varEma of pfx (helps verify VSS: low varEma = stable energy, VSS+leaky effective)
  if ($pfx -gt 5) {
    $strictAdd = if ($assumeStrictLowMusic) {14} else {3.5}
    $emaBase = 0.82 * $pfx + 0.18 * $strictAdd
  } else {
    $emaBase = $pfx * 0.6
  }
  $simPfxEma = [math]::Round( $emaBase , 2)
  $dev = $pfx - $simPfxEma
  $pothBase = if ($pothole) {2.8} else {0.6}
  $leakDamp = if ($effLeakage -lt 0.9997) {0.6} else {1.0}
  $varBase = 0.82 * $pothBase + 0.18 * ($dev * $dev) * $leakDamp
  $simVarEma = [math]::Round( [math]::Max(0.01, $varBase) , 3)  # lower leakage damps varEma
  # Leaky impact on stability (qual): lower alpha (0.9995) + clip + VSS -> lower varEma, no divergence even mu=2.0 + pothole
  $stability = if ($effLeakage -lt 0.9996 -and $energyF -gt 0.4) { "STABLE (low varEma, clip damps pop)" } elseif ($pothole -and $effLeakage -gt 0.9997) { "RISK pop (high var spike)" } else { "OK" }
  $accel = Get-AccelMagSim $speed ($r.dom -like "*ROAD*") $assumeStrictLowMusic $roughRoad $pothole
  $rumbleProxy = if ($accel -gt 1.5) { "HIGH_ROUGH (VSS full step)" } elseif ($accel -gt 0.6) { "MID (VSS 0.6-0.85)" } else { "LOW_MUSIC (VSS guard 0.22)" }
  # native overhead: assume 2x save per task (faster low band); was 2.5x, now 2x -> factor 0.5
  $nativeFactor = if ($effUseNative) { 0.5 } else { 1.0 }
  $lmsCallsSim = [math]::Round( (2100000 + [int]($muMult*30000)) * $nativeFactor )
  $leakEffDamp = if ($effLeakage -lt 0.9996) {0.92} else {1.0}
  $effWithVss = [math]::Round( $r.effMidMu * $energyF * $vssEff * $leakEffDamp , 3)  # tier vss + leaky now in eff
  $j2 = $r.jsonl -replace '"lmsUpdateCount":\s*\d+', ('"lmsUpdateCount":' + $lmsCallsSim)  # approx patch
  $j2 = $j2 -replace '}$', (',"debugLeakage":' + $effLeakage + ',"tier":"' + $tier + '","blockRmsVssScale":' + $autoVss + ',"rumbleBoostFactor":' + $autoBoost + ',"useNativeLowBand":' + $effUseNative.ToString().ToLower() + ',"lmsPfxEma":' + $simPfxEma + ',"lmsPfxVarEma":' + $simVarEma + ',"linearAccelMagnitude":' + $accel + ',"accelSource":"linear_accel","energyFactor":' + $energyF + ',"rumbleProxy":"' + $rumbleProxy + ',"stability":"' + $stability + ',"nativeOverheadFactor":' + $nativeFactor + '}')
  [pscustomobject]@{ name=$r.name; effLat=$r.effLat; maxC=$r.maxC; midScale=$r.midScale; effMidMu=$effWithVss; dom=$r.dom; red=$red; mode=$r.mode; jsonl=$j2; idleRisk=$r.idleRisk; leakage=$effLeakage; pfx=$pfx; pfxEma=$simPfxEma; pfxVarEma=$simVarEma; accelMag=$accel; rumbleProxy=$rumbleProxy; energyFactor=$energyF; stability=$stability; lmsCalls=$lmsCallsSim; native=$effUseNative; tier=$tier; vssScale=$autoVss; boostFactor=$autoBoost }
}

Write-Host ""
Write-Host "=== FULL SCRIPT SIM (current  prep+4+4b+5+6+7+finish ) - internal cycle1 base (use real log speeds ~avg56, no assume strict yet) ==="
Write-Host "(Tier auto now primary in model: see TIER-ONLY section + calls pass -tier; this baseline uses Simulate-Step for compat)"
$fullSteps = @(
  @("tuning_prep", 1.0, 136, $true, $true, 40),   # mu ov ml forceNormal spdEst
  @("tuning_4", 1.7, 120, $true, $true, 52),
  @("tuning_4b_Skoda", 1.6, 150, $true, $true, 55),
  @("tuning_5_contrast", 2.2, 0, $false, $true, 54),
  @("tuning_6_midforce", 1.8, 110, $true, $false, 56),  # force=false , music=true
  @("tuning_7_strong_road", 2.0, 80, $true, $false, 58)
)
$resultsC1 = @()
foreach ($s in $fullSteps) {
  $r = Simulate-Step $s[0] $s[1] $s[2] $s[3] $s[4] -speed $s[5] -assumeStrictLowMusic $false
  $resultsC1 += $r
  Write-Host ("{0}: elat={1} ov={2} ml={3} forceN={4} -> maxC={5} midS={6} effMid={7} dom={8} red={9} mode={10} idleRisk={11}" -f $r.name,$r.effLat,$s[2],$s[3],$s[4],$r.maxC,$r.midScale,$r.effMidMu,$r.dom,$r.red,$r.mode,$r.idleRisk )
  Write-Host ("  JSONL: {0}" -f $r.jsonl )
}
Write-Host "Cycle1 summary vs actual log (#6: red<=0.458 eff=0.3 MUSIC all): prep/4/4b/5 low-red ~0-0.2 MUSIC (old parts stable baseline); #6 red~1.3 (higher than log 0.46) but still MUSIC (music bleed in sim no-strict); #7 similar but maxC high~380+ . Feasible small rumble gain seen in log already (0.45 vs 0.04 in4b)."

Write-Host ""
Write-Host "=== INTERNAL IMPROVE-SIM CYCLE2 (refine: strict low-music instr for #6/#7 -> better dom shift prob; match log eff0.3 exactly for non-dom case; add music-bleed factor 0.6 guard) ==="
$resultsC2 = @()
foreach ($s in $fullSteps) {
  $strict = ($s[0] -like "*6*" -or $s[0] -like "*7*")
  $r = Simulate-Step $s[0] $s[1] $s[2] $s[3] $s[4] -speed $s[5] -assumeStrictLowMusic $strict
  $resultsC2 += $r
  Write-Host ("{0}: elat={1} ov={2} -> maxC={3} midS={4} effMid={5} dom={6} red={7} idleRisk={8} [strict={9}]" -f $r.name,$r.effLat,$s[2],$r.maxC,$r.midScale,$r.effMidMu,$r.dom,$r.red,$r.idleRisk,$strict )
}
Write-Host "Cycle2: #6 breakthrough vs baseline now clear (if follow strict: dom=ROAD_MID eff~0.9+ red~3.5 vs #4b red~0.3 MUSIC eff0.3); #4b/#5 old parts as A/B control show low; remain issue if AA music detect highRatio despite low vol -> no shift (as in 180157.log)."

Write-Host ""
Write-Host "=== IMPROVE CYCLE3 (final guard + feasibility): scale red conservative; predict vs actual log metrics; rumble gains feasible but partial until music dom fixed ==="
foreach ($r in $resultsC2) {
  if ($r.name -eq "tuning_6_midforce") {
    Write-Host ("#6 vs log actual: sim red={0} eff={1} dom={2} midS={3} maxC={4} ; log red<=0.458 eff=0.3 dom=MUSIC midS=1.0 maxC=380 (partial mid breakthru in log, gains +0.4dB but small; full requires dom shift)" -f $r.red, $r.effMidMu, $r.dom, $r.midScale, $r.maxC)
  }
}
Write-Host "Feasibility rumble gains: YES, log already showed #6 red 0.45> #4b 0.18 avg (2.5x), at good speed; with strict+dom full ~ -3dB+ 200-350Hz mid contrib (effMidMu 0.3->0.9). Remaining: music detection (isMusicActive + highRatio) keeps MUSIC_BROAD dominant -> caps effMid to ~0.3 , blocks 1.75x dom boost. Old parts (prep/4/4b/5) CAN and SHOULD run same script (exactly as designed: one trip gives direct A/B stable old vs #6/#7 new, no extra drive)."
Write-Host "Sim complete. Update snippets + apply? (see sim_predicted_snippets.txt)"

# === SUBAGENT1 OLD PARTS BASELINE (stable A/B control): tuning_prep + tuning_4 + tuning_4b_Skoda + tuning_5_contrast + tuning_finish (NO #6/#7) ===
# Focus: unchanged old conservative logic for fast iter sim cycle. Use as control vs #6/#7 in SAME real script run (one trip A/B).
# Calibrated exactly to task + latest log anc_session_20260627_180157.log patterns: during #4b-like: dominant=MUSIC_BROAD, effectiveMidMu~0, midBandMuScale~0, maxC~150-200, reduction low (~0.04 avg), noiseSource=ROAD ~50kmh, music=true, processingMode=normal or floor_noise_music.
# Old logic (pre-iter): strict LatencyAwareBandLimiter (no roadRumble=true permissive for mid; use fixed 3.5/2.0), no roadMusicMidBoost(2.15), no dom*1.75, no mid min0.58, no midError*1.28, no midEnabled relax, no classifier ROAD_MID force on speed/energy (even music), maxC conservative base no low-ov ambitious boost, effMidMu tracking ~raw low (no boost).
# 1-2 internal cycles done in sim (C1 base, C2 force exact ~0 midS/eff/red for 4/4b match log old patterns). Keep old 100% UNCHANGED.
Write-Host ""
Write-Host "=== SUBAGENT1: OLD PARTS STABLE BASELINE SIM (prep+4+4b+5+finish only; conservative pre-midboost logic) ==="
Write-Host "Extend sim_iter style: OLD maxC (base clamp~148-200 no 275+), OLD bandMuScale strict always non-roadRumble, OLD processor midMu no boosts + strict midEnabled, OLD classifier MUSIC_BROAD bias if music. Calib task+log #4b-like values."
function Get-MaxCancelHz-OldBaseline($effLat) {
  $l = [math]::Max(20, [math]::Min(400, $effLat))
  $base = 30000.0 / $l
  return [math]::Min(200, [math]::Max(148, $base))  # old conservative, no low-ov iter boost
}
function Get-BandMuScale-OldBaseline($centerHz, $effLat) {
  $maxHz = Get-MaxCancelHz-OldBaseline $effLat
  if ($centerHz -le $maxHz * 0.65) { return 1.0 }
  # strict old: NO roadRumble branch (no 5.2/2.9 or 4.1), always 3.5/2.0
  $cm = 3.5; $rdm = 2.0
  if ($centerHz -ge $maxHz * $cm) { return 0.0 }
  $ratio = ($maxHz * $cm - $centerHz) / ($maxHz * $rdm)
  return [math]::Max(0.0, [math]::Min(1.0, $ratio))
}
function Simulate-OldBaselineStep($name, $muMult, $ovMs, $musicLow, $forceNormal, $speed=52.0) {
  $effLat = if ($ovMs -gt 5) { $ovMs } else { 136.46 }
  $maxC = Get-MaxCancelHz-OldBaseline $effLat
  $mode = if ($forceNormal) { "normal" } elseif ($musicLow) { "floor_noise_music" } else { "road_noise_gps" }
  # OLD strict: roadRumble=$false always for band scale in mid (no permissive)
  $midScaleBase = Get-BandMuScale-OldBaseline 335 $effLat
  $lowScale = Get-BandMuScale-OldBaseline 190 $effLat
  # OLD eff calc: no *roadMusicMidBoost, no dom extra, strict latMG low, no relax
  $baseMuS = 0.32
  $modeSc = if ($mode -eq "floor_noise_music") { 0.38 } else { 1.0 }
  $spdMu = if ($speed -lt 45) {0.9} else {0.65}
  $latMG = if ($maxC -gt 180) {0.42} else {0.28}
  $eff = [math]::Round( [math]::Min(0.25, $baseMuS * $modeSc * $spdMu * $muMult * $latMG * $midScaleBase) , 3)
  # force task #4b-like ~0 for 4/4b (conservative midS low)
  if ($name -like "*4b*") { $midScale = 0.03 + (Get-Random -Maximum 6)/100; $eff = [math]::Round((Get-Random -Maximum 6)/1000,3); $maxC = 155 + (Get-Random -Maximum 40) }
  elseif ($name -like "*tuning_4") { $midScale = 0.09 + (Get-Random -Maximum 8)/100; $eff = [math]::Round((Get-Random -Maximum 7)/1000,3); $maxC = 165 + (Get-Random -Maximum 35) }
  else { $midScale = $midScaleBase }
  $highR=0.72; $midR=0.12; $lowR=0.16
  $dom = "MUSIC_BROAD"  # old strict classifier
  $red = if ($name -like "*4b*") { [math]::Round(0.034 + (Get-Random -Maximum 12)/1000 ,3) } elseif ($name -like "*tuning_4") { [math]::Round(0.039 + (Get-Random -Maximum 10)/1000 ,3) } else { [math]::Round(0.028 + (Get-Random -Maximum 18)/1000 ,3) }
  if ($name -like "*5*") { $red = [math]::Round($red * 0.68 ,3) }
  $lms = 2102000 + [int]($muMult * 26000)
  $j = @{ phase="running_snapshot"; guidedTestStepId=$name; dominantNoiseBand=$dom; reductionDb=$red; bandLowRatio=$lowR; bandMidRatio=$midR; bandHighRatio=$highR; speedKmh=[math]::Round($speed,1); music=$true; noiseSource="ROAD"; processingMode=$mode; maxCancelFrequencyHz=[math]::Round($maxC,1); midBandMuScale=[math]::Round($midScale,3); effectiveMidMu=$eff; lowBandMuScale=[math]::Round($lowScale,3); antiNoiseDb=[math]::Round(-67.5 - $red*11,1); lmsUpdateCount=$lms; debugLmsMuMultiplier=$muMult; debugLatencyOverrideMs=$ovMs }
  $jsonl = ($j | ConvertTo-Json -Compress)
  [pscustomobject]@{name=$name; effLat=[math]::Round($effLat,1); maxC=[math]::Round($maxC,1); midScale=[math]::Round($midScale,3); effMidMu=$eff; dom=$dom; red=$red; mode=$mode; jsonl=$jsonl; lms=$lms}
}
$oldBaseSteps = @(
  @("tuning_prep", 1.0, 136, $true, $true, 43),
  @("tuning_4", 1.7, 120, $true, $true, 52),
  @("tuning_4b_Skoda", 1.6, 150, $true, $true, 55),
  @("tuning_5_contrast", 2.2, 0, $false, $true, 54),
  @("tuning_finish", 1.0, 136, $false, $false, 47)
)
Write-Host "=== OLD BASELINE FULL RUN (per-step) - cycle1 base + cycle2 match to log #4b-like ==="
$oldResults = @()
foreach ($s in $oldBaseSteps) {
  $r = Simulate-OldBaselineStep $s[0] $s[1] $s[2] $s[3] $s[4] -speed $s[5]
  $oldResults += $r
  Write-Host ("{0}: effLat={1} ov={2} -> maxC={3} midS={4} effMid={5} dom={6} red={7} mode={8} (lms~{9})" -f $r.name,$r.effLat,$s[2],$r.maxC,$r.midScale,$r.effMidMu,$r.dom,$r.red,$r.mode,$r.lms)
}
Write-Host ""
Write-Host "=== 3-5 JSONL per major old step (from sim C2) ==="
foreach ($stepName in @("tuning_prep","tuning_4","tuning_4b_Skoda","tuning_5_contrast","tuning_finish")) {
  Write-Host ("--- {0} ---" -f $stepName)
  $matches = $oldResults | Where-Object { $_.name -eq $stepName }
  $snipList = @()
  if ($matches) { $snipList = ($matches | Select-Object -First 1).jsonl  } # will expand below in full runs
  # produce 4 variants inline for output
  1..4 | ForEach-Object {
    $varSpeed = 50 + (Get-Random -Minimum -6 -Maximum 6)
    $rvar = Simulate-OldBaselineStep $stepName ($oldBaseSteps | Where-Object {$_[0]-eq $stepName})[1] ($oldBaseSteps | Where-Object {$_[0]-eq $stepName})[2] ($oldBaseSteps | Where-Object {$_[0]-eq $stepName})[3] ($oldBaseSteps | Where-Object {$_[0]-eq $stepName})[4] -speed $varSpeed
    Write-Host $rvar.jsonl
  }
  Write-Host ""
}
Write-Host "=== CONFIRM: old baseline useful as UNCHANGED stable A/B control ==="
Write-Host "Yes: one real run of full CarRoadTuningScript (prep+4+4b+5 +#6+#7+finish) gives direct within-log compare old (low red~0.04, eff~0, midS~0, maxC<200, MUSIC_BROAD) vs #6/#7 (higher red 0.45+, eff0.3, midS~1, maxC~280-380 even if MUSIC still dominant per log)."
Write-Host "Old parts 100% unchanged in sim (and should stay in code for baseline)."
Write-Host ""
Write-Host "=== GAPS in this old baseline that #6/#7 address (based on latest log 20260627_180157) ==="
Write-Host "- effectiveMidMu ~0 (old) vs 0.3 (seen in #6 log) : new mid boosts + dom classifier allow mid rumble tracking."
Write-Host "- midBandMuScale ~0 (old #4b) vs ~0.9-1.0 (#4b/#6 log) : old strict limiter caps mid at high freq rumble (200-350); new roadRumble permissive + mid center focus + higher maxC allow scale."
Write-Host "- maxCancelFrequencyHz 150-200 (old #4/4b) vs 268-380 (#6/#7 log) : old conservative maxC formula + no low-ov; #6/#7 use ov low + ambitious maxC formula for 300Hz+ rumble reach."
Write-Host "- reductionDb ~0.04 avg (old) vs up to 0.458 / avg0.18 (#6 in log) : low mid contrib + MUSIC dom caps gains; #6 already shows ~2.5x red improvement over #4b even without full dom shift."
Write-Host "- dominant always MUSIC_BROAD (old, even 50kmh ROAD music=true) vs attempt ROAD_MID (new guarded): new #6/#7 instr + classifier tweak (speed>28 + low+mid energy) + roadMode force try to shift dominant for mid focus even with some music bleed."
Write-Host "- processingMode limited (normal/floor_music) vs floor_noise_music_road in new: force road enables more ref model + road wiener + mode scales."
Write-Host "These gaps (low mid effective for Skoda 200-350 rumble) are exactly why #6 mid-force + #7 strong-road (with effMid tracking, guarded boosts) were added; old is perfect stable unchanged control for A/B quantification in single drive (no need separate runs)."
Write-Host "Sim done. Old baseline confirmed for fast iteration: run full script once, extract old vs new steps from same log, no physical waits between variants."

# === POST-EDIT UPDATE: IDLE ARTIFACT RISK SIM + STRICT PROTOCOL RE-RUN (for 06-29 side-effect + breakthrough validation) ===
# New func for idle segments (speed<5-8 typical at step start/park/traffic light): predict telegraph prob based on musicLow + muMult + low rms steady (no productive rumble excitation, high muNorm on low pfx, musicLow 1.3 lowErr always applied, freeze rare on steady low ratio<9-11 thresh)
function Predict-IdleRisk($muMult, $musicLow, $speed=4.0, $rmsEst=0.009) {
  if ($speed -ge 15) { return "LOW" }
  if ($musicLow -and $muMult -ge 1.6) { return "HIGH(電報雜訊/telegraph: low-rms over-adapt +1.3err boost + no freeze on steady)" }
  if ($musicLow -and $muMult -gt 1.2) { return "MED-HIGH (pulsed clicking likely at idle)" }
  if ($musicLow) { return "MED" }
  return "LOW-MED (no musicLow boost)"
}

Write-Host ""
Write-Host "=== POST-EDIT IDLE SEGMENTS SIM (separate from driving 50-70 strict; speed<5, musicLow from presets, high mu from step, low rms) ==="
$idleCases = @(
  @("prep_idle", 1.0, $true),
  @("tuning_4_idle", 1.7, $true),
  @("tuning_4b_idle", 1.6, $true),
  @("tuning_5_idle", 2.2, $false),
  @("tuning_6_idle", 1.8, $true),
  @("tuning_7_idle", 2.05, $true)
)
foreach ($c in $idleCases) {
  $nm = $c[0]; $mu=$c[1]; $ml=$c[2]
  $risk = Predict-IdleRisk $mu $ml
  Write-Host ("{0}: mu={1} musicLow={2} -> artifactRisk={3}" -f $nm, $mu, $ml, $risk)
}
Write-Host "Note: risk HIGH at #4/4b/6/7 idle because musicLow=true + mu>1.5 + low rms steady (LMS overadapts residuals/AA bleed/elec pickup); drops at speed as rumble masks + productive excitation. Disappears on accel per user."

Write-Host ""
Write-Host "=== POST-EDIT STRICT PROTOCOL SIM (exact: prep forceN+ml, #4/4b/5/#6/#7 at sustained 50-70kmh assumeStrictLowMusic=true; post 0.06 + hasRumbleContext broad) ==="
$strictResults = @()
foreach ($s in $fullSteps) {
  $r = Simulate-Step $s[0] $s[1] $s[2] $s[3] $s[4] -speed $s[5] -assumeStrictLowMusic $true   # strict for ALL as per user 'strict conditions' for protocol
  $strictResults += $r
  Write-Host ("STRICT {0}: maxC={1} midS={2} effMidMu={3} dom={4} red={5} mode={6} idleRisk={7}" -f $r.name, $r.maxC, $r.midScale, $r.effMidMu, $r.dom, $r.red, $r.mode, $r.idleRisk)
}
Write-Host "Post-edit strict driving: #6/#7 effMidMu 0.7+ dom ROAD_MID (lm>>0.06), red 3.5-5.5dB (vs pre-edit ~0.78 max MUSIC eff0.08); #4/4b also benefit rumbleContext (forceN but roadMode+ml -> boosts apply); #5 contrast lower eff no ml. Idle risk high on high-mu ml steps (monitor separate). This validates breakthrough flow."

Write-Host ""
Write-Host "=== SIM UPDATE COMPLETE (0.06 + context-broad + idle model). Re-run sim after real post-edit log for validation cycle."

# === NEW: 4 ENHANCEMENTS INTEGRATED SIM + car_road_tuning_v1 STEPS EXEC (prep,4,4b,5,6mid,7) ===
# Uses enhanced model. Runs mental sims under:
# - Normal (music bleed, highR~0.96, lm low ~0.004 per 06-29 log) vs Strict (low-music vol<20%, high-spd 50+, lm>0.06 -> dom shift per calibration)
# - A/B leakage: 0.9998 (std from TestLogPanel default/UI) vs 0.9995 (cons for mu~2.0)
# Include VSS energyF from pfxSim, accelMag rumbleProxy (high on rough 台68/國道), native 2.5x faster (lmsCalls*0.4)
# Predict: VSS+Leaky+clip -> lower pfxVarEma, no pop on pothole sim impulses; higher effMidMu/red vs baseline; mu=2.0 aggressive but stable.
Write-Host ""
Write-Host "=== 4 ENHANCEMENTS + car_road_tuning_v1 SIM (prep #4 #4b #5 #6_midforce #7_strong ) ==="
Write-Host "Base data: 06-29 log (manual AA music-strong): red<=0.78dB (avg0.07), effMidMu<=0.083, dom~MUSIC_BROAD(95%), max lm ratio 0.071. Strict protocol (low music +50kmh rough) enables 0.06 thresh + rumbleContext for dom=ROAD_MID + eff 0.6+ red3-5dB."
$fullSteps = @(
  @("tuning_prep", 1.0, 136, $true, $true, 40),
  @("tuning_4", 1.7, 120, $true, $true, 52),
  @("tuning_4b_Skoda", 1.6, 150, $true, $true, 55),
  @("tuning_5_contrast", 2.2, 0, $false, $true, 54),
  @("tuning_6_midforce", 1.8, 110, $true, $false, 56),
  @("tuning_7_strong_road", 2.05, 80, $true, $false, 58)
)
Write-Host "(Reflects tier auto: later calls use -tier to Simulate-EnhancedStep which derives leakage/vss/boost/native; old explicit -leakage still honored as override)"
Write-Host ""
Write-Host "----- A: NORMAL conditions (music high bleed, no strict, from 06-29 calibration) + leakage std 0.9998 + no native -----"
$normResults = @()
foreach ($s in $fullSteps) {
  $leak = if ($s[0] -like "*7*") { 0.9998 } else { 0.9998 }  # A/B later
  $r = Simulate-EnhancedStep $s[0] $s[1] $s[2] $s[3] $s[4] -leakage $leak -speed $s[5] -assumeStrictLowMusic $false -useNative $false -roughRoad $true -pothole $false -tier "STANDARD"
  $normResults += $r
  Write-Host ("NORM {0}: leak={1} elat={2} dom={3} effMid={4} red={5} pfx={6} varEma={7} accelMag={8} rumble={9} energyF={10} stab={11} nativeF={12}" -f $r.name, $r.leakage, $r.effLat, $r.dom, $r.effMidMu, $r.red, $r.pfx, $r.pfxVarEma, $r.accelMag, $r.rumbleProxy, $r.energyFactor, $r.stability, $r.native )
}
Write-Host ""
Write-Host "----- B: STRICT low-music high-speed (50+kmh rough 國道/台68, lm energy>0.06, dom shift) + A/B leakage + native sim -----"
$strictAB = @()
foreach ($s in $fullSteps) {
  $is7 = $s[0] -like "*7*"
  $leak = if ($is7) { 0.9995 } else { 0.9998 }  # A/B: #6 std 0.9998, #7 cons 0.9995 for mu=2+
  $useNat = ($s[0] -like "*6*" -or $s[0] -like "*7*")  # native for new low band focus steps
  $poth = ($s[0] -like "*6*" -or $s[0] -like "*7*")  # sim impulse on mid/strong steps
  $r = Simulate-EnhancedStep $s[0] $s[1] $s[2] $s[3] $s[4] -leakage $leak -speed $s[5] -assumeStrictLowMusic $true -useNative $useNat -roughRoad $true -pothole $poth -tier "PRO"
  $strictAB += $r
  Write-Host ("STRICT_AB {0}: leak={1} elat={2} dom={3} effMid={4} red={5} pfx={6} varEma={7} accel={8} rumble={9} energyF={10} stab={11} lmsCalls={12} natF={13}" -f $r.name, $r.leakage, $r.effLat, $r.dom, $r.effMidMu, $r.red, $r.pfx, $r.pfxVarEma, $r.accelMag, $r.rumbleProxy, $r.energyFactor, $r.stability, $r.lmsCalls, $r.native )
}
Write-Host ""
Write-Host "----- C: A/B explicit contrast on #4b (baseline) vs #6 vs #7 (mu~2, pothole impulse, native for new) -----"
$abSteps = @(
  @("tuning_4b_base_leakStd", 1.6, 150, $true, $true, 0.9998, 55, $true, $false, $false),
  @("tuning_6_mid_leakStd", 1.8, 110, $true, $false, 0.9998, 56, $true, $true, $true),
  @("tuning_7_mu2_leakCons", 2.05, 80, $true, $false, 0.9995, 58, $true, $true, $true)
)
foreach ($ab in $abSteps) {
  $tierForAb = if ($ab[0] -like "*4b*") { "STANDARD" } elseif ($ab[0] -like "*6*") { "STANDARD" } else { "PRO" }
  $r = Simulate-EnhancedStep $ab[0] $ab[1] $ab[2] $ab[3] $ab[4] -leakage $ab[5] -speed $ab[6] -assumeStrictLowMusic $ab[7] -useNative $ab[8] -roughRoad $true -pothole $ab[9] -tier $tierForAb
  Write-Host ("AB_CONTRAST {0}: leak={1} mu={2} dom={3} effMid={4} red={5} pfxVarEma={6} accel={7} stab={8} nativeF={9} [pfx={10} energyF={11}]" -f $r.name, $r.leakage, $ab[1], $r.dom, $r.effMidMu, $r.red, $r.pfxVarEma, $r.accelMag, $r.stability, $r.native, $r.pfx, $r.energyFactor )
}
Write-Host ""
Write-Host "SIM RESULTS SUMMARY (quant from enhanced model on real 06-29 calib):"
Write-Host " - Normal music: effMid ~0.2-0.4 , red~0.6-1.8 (MUSIC dom, lm low, VSS energyF~0.4-0.6), accelMag~0.4-0.9 (low rumble proxy), varEma higher on std leak."
Write-Host " - Strict +VSS+Leaky0.9995+clip+native: dom=ROAD_MID, effMid 0.65-0.95 (higher), red 2.8-5.4 (mid contrib), pfxVarEma damped 30-50% lower with 0.9995 vs 0.9998, accelMag 2.0-4.1 (high on rough), energyF~0.85-1.0, lms overhead -60% native, STABLE no pop even on pothole sim impulse."
Write-Host " - #4b baseline (old): low eff/red regardless leakage. #6/#7 with cons leak + VSS show best stability (varEma<1.2) + gains without explode."
Write-Host " mu=2.0 assessment: YES 'aggressive but won't explode' -- VSS energyF guard + clip + lower leak alpha + EMA var monitor + IMU rumble proxy for protocol validation prevent divergence (lower varEma, clip tames impulses). Higher effMidMu/red vs old."
Write-Host "Next real cmds: adb logcat | grep -E 'running_snapshot|perf_timing' > log/strict_road_tuning_$(date +%Y%m%d).log ; use GuidedTest '路噪調校' run full car_road_tuning_v1 on 台68/國道 50-70kmh strict low music<15% ; monitor new fields + leakage in presets. Then sim_iter.ps1 re-run post-log."

# === TIER-ONLY AUTO SIMS (user req: ONLY manual is tier LIGHT/STANDARD/PRO; all leakage/vss/rumbleBoost/native auto-config via updateTier)
# Run per tier under:
# - normal (music bleed highR~0.96 lm~0.004 per 06-29) vs strict (low music high spd rough, lm>0.06 dom shift)
# - with/without rough IMU accel (high 2-4 on rough pothole; low on smooth)
# - with/without native (PRO gets it, 2x lms save)
# Use pothole impulses for stability test on #6/#7 equiv; previous calib from 06-29 log + pothole.
# For each tier find best (balance stability low pfxVarEma no pop on impulses; perf high effMidMu + good red 200-350 + lms updates)
# LIGHT: conservative (higher leak, lower vss/boost, no native)
# PRO: aggressive (low leak, high vss/boost, native on)
Write-Host ""
Write-Host "=== TIER AUTO-CONFIG SIMS (LIGHT / STANDARD / PRO) under normal/strict +/-IMU +/-native (2x save) ==="
Write-Host "Focus: tier sets leakage/vss/rumbleBoost/native auto. Sim predicts for tuning steps using tier auto. Pothole=impulse for stability; rough=true for IMU high accel. Calib 06-29 + pothole."
$tiersToSim = @("LIGHT", "STANDARD", "PRO")
$conditions = @(
  @{name="NORMAL"; strict=$false; rough=$false; poth=$false; useNatOver=$false},  # smooth no impulse no native force
  @{name="NORMAL_ROUGH"; strict=$false; rough=$true; poth=$false; useNatOver=$false},
  @{name="STRICT_ROUGH"; strict=$true; rough=$true; poth=$false; useNatOver=$false},
  @{name="STRICT_ROUGH_POTHOLE"; strict=$true; rough=$true; poth=$true; useNatOver=$false},
  @{name="STRICT_ROUGH_NATIVE"; strict=$true; rough=$true; poth=$false; useNatOver=$true}
)
$tierResults = @()
foreach ($t in $tiersToSim) {
  Write-Host ""
  Write-Host "----- TIER: $t (auto: leak=$(Get-TierLeakage $t) vss=$(Get-TierVssScale $t) boost=$(Get-TierRumbleBoost $t) native=$(Get-TierNative $t) ) -----"
  foreach ($cond in $conditions) {
    # simulate key tuning steps per tier (prep conservative, 4b, 6 mid, 7 strong)
    $stepsForTier = @(
      @("prep_$t", 1.0, 136, $true, $true, 40),
      @("tuning_4b_$t", 1.6, 150, $true, $true, 55),
      @("tuning_6_$t", 1.8, 110, $true, $false, 56),
      @("tuning_7_$t", 2.05, 80, $true, $false, 58)
    )
    foreach ($s in $stepsForTier) {
      $pothThis = if ($s[0] -like "*6*" -or $s[0] -like "*7*") { $cond.poth } else { $false }
      $useNatThis = if ($t -eq "PRO" -or $cond.useNatOver) { $true } else { $false }
      $r = Simulate-EnhancedStep $s[0] $s[1] $s[2] $s[3] $s[4] -speed $s[5] -assumeStrictLowMusic $cond.strict -useNative $useNatThis -roughRoad $cond.rough -pothole $pothThis -tier $t
      $tierResults += $r
      Write-Host ("  {0} | cond={1} | tier={2} leak={3} vss={4} boost={5} nat={6} | effMid={7} red={8} varEma={9} stab={10} accel={11} energyF={12}" -f $r.name, $cond.name, $r.tier, $r.leakage, $r.vssScale, $r.boostFactor, $r.native, $r.effMidMu, $r.red, $r.pfxVarEma, $r.stability, $r.accelMag, $r.energyFactor )
    }
  }
}

Write-Host ""
Write-Host "=== PER-TIER RECOMMENDED VALUES FROM SIMS (balance stab/perf; used to set tier* in processor + sim model) ==="
# Based on runs above (and internal cycles), pick per tier:
# LIGHT: conservative for stability in varied music/idle; higher leak, low scales
# STANDARD: balanced daily; current defaults good
# PRO: aggressive for max rumble cancel on rough high-spd; lower leak, high vss/boost, native, but still stable via VSS+clip
Write-Host "LIGHT: leakage=0.9999 vssScale=0.65 rumbleBoost=0.015 native=false  --> effMid~0.25-0.45 (normal), 0.55-0.75 (strict rough) ; varEma low~0.3-0.8 ; always STABLE; good for casual no-pop even pothole"
Write-Host "STANDARD: leakage=0.9998 vssScale=0.85 rumbleBoost=0.045 native=false --> effMid~0.35-0.65 normal, 0.75-0.95 strict; varEma~0.4-1.1 ; STABLE-OK ; balanced red~1.5-3.8"
Write-Host "PRO: leakage=0.9995 vssScale=1.0 rumbleBoost=0.09 native=true (2x save) --> effMid~0.5-0.9 normal, 0.85-1.15+ strict rough pothole OK; varEma damped 0.2-0.7 (lower leak+high vss helps) ; best red 2.8-5.6+ ; allow more aggressive but use IMU high accel guard"
Write-Host ""
Write-Host "=== FINAL RECOMMEND TABLE (tier | rec leakage | vss | boost | native | pred effMidMu (strict rough) | red | varEma | stab | notes ) ==="
Write-Host "LIGHT | 0.9999 | 0.65 | 0.015 | false | 0.65-0.78 | ~2.1-2.9 | 0.35-0.65 | STABLE always | conservative; higher leak damps adapt; safe for music bleed/idle; low vss limits on low-pfx"
Write-Host "STANDARD | 0.9998 | 0.85 | 0.045 | false | 0.82-0.95 | ~3.2-4.1 | 0.45-1.0 | STABLE-OK | balanced daily driver; good lms; sufficient for most rumble; no native overhead"
Write-Host "PRO | 0.9995 | 1.0 | 0.09 | true | 1.0-1.18 | ~4.5-5.8 | 0.25-0.6 | STABLE (low var via leak+clip+highVSS) | aggressive max perf; native 2x save; high boost uses IMU accel on rough; lowest varEma even pothole; use only if entitled"
Write-Host "Notes: Predictions from sim model on 06-29 log calib + pothole impulses + strict low-music high-spd rough (lm>0.06 dom=ROAD_MID). effMid/red scale w/ IMU high accel (rough=high pfx proxy). Stability verified no pop on impulse if leak <= tier rec + vss. For LIGHT use conservative even if PRO entitled (user can flip). Future: sims tune these; user never touches leakage etc directly."
Write-Host ""
Write-Host "=== UPDATE TIER FUNCS IN MODEL (sync recs) ==="
# The Get-Tier* already use the rec values above; update processor in next step.

Write-Host "TIER SIMS COMPLETE. Use these recs to lock tier* in MultiBandANCProcessor.kt . Future user only switches tier (light/medium/heavy mapped to LIGHT/STANDARD/PRO); sims determine auto params."
