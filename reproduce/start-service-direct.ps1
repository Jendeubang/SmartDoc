param(
    [Parameter(Mandatory=$true)]
    [string]$Module,
    [string]$Profile = ""
)

$ProjectRoot = "F:\DocAI\DocAI-main"
$EnvFile = Join-Path $ProjectRoot "reproduce\.env.local"

# Load environment variables
Get-Content $EnvFile | Where-Object { $_ -and -not $_.StartsWith('#') } | ForEach-Object {
    $name, $value = $_ -split '=', 2
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}

$ModuleDir = Join-Path $ProjectRoot $Module

if ($Profile -ne "") {
    mvn -gs "$ProjectRoot\reproduce\maven-settings-local.xml" -f "$ModuleDir\pom.xml" spring-boot:run -Dspring-boot.run.profiles=$Profile
} else {
    mvn -gs "$ProjectRoot\reproduce\maven-settings-local.xml" -f "$ModuleDir\pom.xml" spring-boot:run
}
