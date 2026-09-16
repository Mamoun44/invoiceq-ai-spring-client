param([switch]$Bootstrap)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if (-not $env:JAVA_HOME) {
    $localJdk = Join-Path $env:USERPROFILE '.jdks\openjdk-26.0.2'
    if (Test-Path -LiteralPath $localJdk) { $env:JAVA_HOME = $localJdk }
}
$env:DB_URL = 'jdbc:postgresql://localhost:5432/invoiceAi?currentSchema=invoice_ai'
$env:DB_USERNAME = 'postgres'
$databasePassword = Read-Host 'PostgreSQL password for postgres (not saved)' -AsSecureString
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', $databasePassword).Password
try {
    if ($Bootstrap) {
        $env:INVOICE_BOOTSTRAP_ENABLED = 'true'
        $env:INVOICE_BOOTSTRAP_COMPANY_CODE = Read-Host 'New company code (letters, numbers, underscore or hyphen)'
        $env:INVOICE_BOOTSTRAP_COMPANY_NAME = Read-Host 'Company name'
        $env:INVOICE_BOOTSTRAP_EMAIL = Read-Host 'New administrator email'
        $adminPassword = Read-Host 'New administrator password (12 to 72 UTF-8 bytes)' -AsSecureString
        $env:INVOICE_BOOTSTRAP_PASSWORD = [System.Net.NetworkCredential]::new('', $adminPassword).Password
        # Use an ephemeral web port so provisioning does not conflict with 8080.
        & .\mvnw.cmd spring-boot:run '-Dspring-boot.run.arguments=--server.port=0'
    } else {
        $env:INVOICE_BOOTSTRAP_ENABLED = 'false'
        & .\mvnw.cmd spring-boot:run
    }
    if ($LASTEXITCODE -ne 0) { throw 'Spring exited with an error; inspect the preceding output.' }
} finally {
    Remove-Item Env:DB_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:INVOICE_BOOTSTRAP_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:INVOICE_BOOTSTRAP_ENABLED -ErrorAction SilentlyContinue
}

