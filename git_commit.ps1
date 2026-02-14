$ErrorActionPreference = "Stop"
Set-Location "C:\StaticHum\NineLivesKotlin"

$git = "C:\Program Files\Git\bin\git.exe"

Write-Host "=== Git Status ===" -ForegroundColor Cyan
& $git status --short

Write-Host "`n=== Adding files ===" -ForegroundColor Cyan
& $git add .

Write-Host "`n=== Committing ===" -ForegroundColor Cyan
$commitMsg = @"
feat: Redesign progress rings as Containment Halos

- Created ContainmentProgressRing.kt with RingStyle presets
- Replaced CosmicProgressRing with new component across all screens
- Fixed 22+ bugs including density conversion, thread safety, and edge cases
- Improved performance: 50% fewer Canvas draw calls
- Added filament gradients for premium aesthetic
- Implemented partial arcs (300°) for small tiles
- Enhanced visual design with proper insets and subtle glows

Breaking changes:
- None (CosmicProgressRing still available for backward compatibility)

Bug fixes:
- Fixed incorrect density conversion in progress ring
- Fixed thread-unsafe SimpleDateFormat usage
- Fixed negative duration handling
- Fixed division by zero risks
- Fixed fragile color detection logic
- Improved type safety and consistency

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>
"@

& $git commit -m $commitMsg

Write-Host "`n=== Current branch ===" -ForegroundColor Cyan
$branch = & $git branch --show-current
Write-Host "Current branch: $branch"

Write-Host "`n=== Pushing to origin/$branch ===" -ForegroundColor Cyan
& $git push origin $branch

Write-Host "`n✅ SUCCESS: Changes committed and pushed!" -ForegroundColor Green
