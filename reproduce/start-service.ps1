param(
    [Parameter(Mandatory=$true)]
    [string]$Module
)

$ProjectRoot = "F:\DocAI\DocAI-main"
$EnvFile = Join-Path $ProjectRoot "reproduce\.env.local"

# Load environment variables
Get-Content $EnvFile | Where-Object { $_ -and -not $_.StartsWith('#') } | ForEach-Object {
    $name, $value = $_ -split '=', 2
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}

# Run the specified module
mvn -gs "$ProjectRoot\reproduce\maven-settings-local.xml" -f "$ProjectRoot\pom.xml" -pl $Module -am spring-boot:run
