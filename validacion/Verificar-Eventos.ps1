[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Workorders,
    [Parameter(Mandatory)][string]$Audit,
    [Parameter(Mandatory)][string]$Bff,
    [string]$Frontend,
    [string]$Bootstrap = 'localhost:9092,localhost:9093,localhost:9094'
)
$ErrorActionPreference = 'Stop'
foreach ($broker in $Bootstrap.Split(',')) {
    if ($broker -notmatch '^(localhost|127\.0\.0\.1):[0-9]+$') { throw 'Solo brokers locales de prueba' }
}
$Workorders = (Resolve-Path -LiteralPath $Workorders).Path
$Audit = (Resolve-Path -LiteralPath $Audit).Path
$Bff = (Resolve-Path -LiteralPath $Bff).Path
if ($Frontend) { $Frontend = (Resolve-Path -LiteralPath $Frontend).Path }
$salida = Join-Path (Split-Path $PSScriptRoot -Parent) 'evidencias-locales'
New-Item -ItemType Directory -Force -Path $salida | Out-Null

function Invoke-Maven([string]$Repo, [string]$Log, [string[]]$MavenArgs) {
    Push-Location -LiteralPath $Repo
    try {
        & .\mvnw.cmd @MavenArgs *> (Join-Path $salida $Log)
        if ($LASTEXITCODE -ne 0) { throw "Falló $Log; revisar evidencias-locales" }
    } finally { Pop-Location }
}

# Las pruebas usan sus perfiles H2, no las variables Oracle del despliegue.
Write-Host '1/5 Workorders: publicación real, no-op y rollback'
Invoke-Maven $Workorders 'workorders-real.log' @('-q', '-Dtest=KafkaEdicionesRealTests', '-Ddigitalfix.pruebas.kafka=true', "-Ddigitalfix.pruebas.kafka.bootstrap=$Bootstrap", 'test')
Write-Host '2/5 Audit: persistencia, HTTP, reintentos y DLT'
Invoke-Maven $Audit 'audit-real.log' @('-q', '-Dtest=KafkaAuditRealTests,KafkaDltFalloRealTests', '-Ddigitalfix.pruebas.kafka=true', "-Ddigitalfix.pruebas.kafka.bootstrap=$Bootstrap", 'test')
Write-Host '3/5 Classpath de Audit con H2; pruebas y paquete del BFF'
Invoke-Maven $Audit 'audit-classpath.log' @('-q', '-DskipTests', 'test-compile', 'dependency:build-classpath', '-Dmdep.includeScope=test', '-Dmdep.outputFile=target/classpath-prueba.txt')
Invoke-Maven $Bff 'bff-suite.log' @('-q', 'package', 'dependency:build-classpath', '-Dmdep.includeScope=test', '-Dmdep.outputFile=target/classpath-prueba.txt')
Write-Host '4/5 Compilar verificador de aplicaciones locales'
$depsAudit = (Get-Content -Raw -LiteralPath (Join-Path $Audit 'target/classpath-prueba.txt')).Trim()
& javac -cp $depsAudit -d $salida (Join-Path $PSScriptRoot 'VerificarHistorial.java')
if ($LASTEXITCODE -ne 0) { throw 'No compiló el verificador; requiere JDK 21' }
Write-Host '5/5 HTTP real: Audit + BFF + vista Angular opcional'
$argumentos = @('-cp', "$salida$([IO.Path]::PathSeparator)$depsAudit", 'VerificarHistorial', $Audit, $Bff, $salida, $Bootstrap)
if ($Frontend) { $argumentos += $Frontend }
& java @argumentos *> (Join-Path $salida 'http-conjunto.log')
if ($LASTEXITCODE -ne 0) { throw 'Falló HTTP conjunto; revisar logs por aplicación' }
Select-String -LiteralPath (Join-Path $salida 'http-conjunto.log') -Pattern 'HTTP_REAL OK' | ForEach-Object { $_.Line }
Write-Host "Evidencias locales: $salida"
