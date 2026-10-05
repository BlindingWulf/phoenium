# Stage the freshly built product JAR under a hashed name in the continuation audit root and
# start run_installed.py detached with correct quoting. Usage:
#   launch_installed.ps1 -Name <fresh-name> -Workers 1|4 -Group <group> [-Cards <card>] [-ExplicitTopology] [-Restart]
param(
    [Parameter(Mandatory)][string]$Name,
    [Parameter(Mandatory)][ValidateSet(1, 4)][int]$Workers,
    [string]$Group = 'legacy',
    [string]$Cards = '',
    [switch]$ExplicitTopology,
    [string]$ExplicitBWidth = '',
    [string]$ExplicitSeparateB = '',
    [switch]$Restart,
    [string]$Candidate = ''
)
$wt = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$aud = Join-Path (Split-Path (Split-Path $wt -Parent) -Parent) '.audit\sonnet-stage7-closeout-20261004'
if (-not $Candidate) { $Candidate = Join-Path $wt 'build\libs\phoenium-0.2.0-stage2.jar' }
$hash = (Get-FileHash $Candidate -Algorithm SHA256).Hash
$staged = Join-Path $aud ('candidate\phoenium-dev-' + $hash.Substring(0, 12).ToLower() + '.jar')
if (-not (Test-Path $staged)) { Copy-Item $Candidate $staged }
$out = Join-Path $aud "logs\$Name.out"
New-Item -ItemType Directory -Force (Split-Path $out) | Out-Null
$a = @('-u', 'scripts\stage7\run_installed.py', $Name, "$Workers", "--group=$Group", "--candidate=`"$staged`"")
if ($Cards) { $a += "--cards=$Cards" }
if ($ExplicitTopology) { $a += '--explicit-topology' }
if ($ExplicitBWidth) { $a += "--explicit-b-width=$ExplicitBWidth" }
if ($ExplicitSeparateB) { $a += "--explicit-separate-b=$ExplicitSeparateB" }
if ($Restart) { $a += '--restart' }
$p = Start-Process -FilePath python -ArgumentList $a -WorkingDirectory $wt -RedirectStandardOutput $out `
    -RedirectStandardError "$out.err" -NoNewWindow -PassThru
"pid=$($p.Id) candidate=$hash out=$out"
