$ErrorActionPreference = "Stop"
<# Java 17 JDK를 탐지해 하위 실행 명령의 런타임을 고정하는 래퍼입니다. #>

param(
  [Parameter(ValueFromRemainingArguments = $true)]
  [string[]] $Command
)

function Test-Java17Home {
  param([string] $Candidate)

  if ([string]::IsNullOrWhiteSpace($Candidate)) {
    return $false
  }

  $Java = Join-Path $Candidate "bin/java"
  if (-not (Test-Path $Java)) {
    $Java = Join-Path $Candidate "bin/java.exe"
  }
  if (-not (Test-Path $Java)) {
    return $false
  }

  $Version = & $Java -version 2>&1 | Select-Object -First 1
  return $Version -match 'version "17\.'
}

function Resolve-Java17Home {
  $Candidates = @(
    $env:STOCKRUSH_JAVA17_HOME,
    $env:JAVA_HOME,
    "C:\Program Files\Eclipse Adoptium\jdk-17",
    "C:\Program Files\Microsoft\jdk-17",
    "C:\Program Files\Java\jdk-17"
  )

  foreach ($Candidate in $Candidates) {
    if (Test-Java17Home $Candidate) {
      return $Candidate
    }
  }

  throw "Java 17 JDK not found. Set STOCKRUSH_JAVA17_HOME to the installed JDK 17 path and retry."
}

if ($Command.Count -eq 0) {
  Write-Host "Usage: .\scripts\with-java17.ps1 <command> [args...]"
  Write-Host ""
  Write-Host "Runs a command with JAVA_HOME set to a Java 17 JDK."
  Write-Host ""
  Write-Host "Override detection with:"
  Write-Host '  $env:STOCKRUSH_JAVA17_HOME = "C:\path\to\jdk-17"'
  exit 2
}

$env:JAVA_HOME = Resolve-Java17Home
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

$Executable = $Command[0]
$Arguments = @()
if ($Command.Count -gt 1) {
  $Arguments = $Command[1..($Command.Count - 1)]
}

& $Executable @Arguments
exit $LASTEXITCODE
