$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$dataRoot = Join-Path $projectRoot 'data'
if (-not (Test-Path (Join-Path $dataRoot 'inbox'))) { throw 'Run this script from the project containing data/inbox.' }
$folders = @('inbox', 'attachments', 'reviews', 'ai-reports', 'ai-cache') |
    ForEach-Object { Join-Path $dataRoot $_ } | Where-Object { Test-Path $_ }
$destination = Join-Path $projectRoot ('shipping-data-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.zip')
Compress-Archive -Path $folders -DestinationPath $destination -CompressionLevel Optimal
Write-Host "Created: $destination"
Write-Host 'Upload this ZIP only to your signed-in Shipping Verifier storage page. Do not commit it to GitHub.'
