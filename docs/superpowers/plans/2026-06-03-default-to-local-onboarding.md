# Default to Local + First-Run Onboarding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Local the default mode and add a one-time first-run Welcome screen (Local vs Server) plus a collapsible "how to load books" guide, so a fresh install lands a broad audience straight into a usable Local flow.

**Architecture:** Add an `onboardingComplete` flag and flip the `appMode` default to `LOCAL` in `AppSettings`. A `SettingsManager.isLoaded` gate lets `MainActivity` wait for the async settings load before choosing the NavHost start destination (`WELCOME` if not onboarded, else `HOME`). The Welcome screen persists the user's choice via a pure `applyOnboardingChoice` transform and routes into the existing Settings screen. Load-bearing logic lives in small pure functions that are unit-tested; Compose/ViewModel/Activity glue is compile- and behavior-verified.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt (`hiltViewModel`), kotlinx.serialization, JUnit4 unit tests, Gradle.

**Branch:** `feat/default-to-local-onboarding` (already created; spec at `docs/superpowers/specs/2026-06-03-default-to-local-and-onboarding-design.md`).

---

## File Structure

**Create:**
- `app/src/main/java/com/ninelivesaudio/app/ui/onboarding/OnboardingChoice.kt` — pure transform applying the onboarding choice to `AppSettings`.
- `app/src/main/java/com/ninelivesaudio/app/ui/onboarding/WelcomeViewModel.kt` — Hilt ViewModel persisting the choice through `SettingsManager`.
- `app/src/main/java/com/ninelivesaudio/app/ui/onboarding/WelcomeScreen.kt` — first-run screen with two equal cards.
- `app/src/main/java/com/ninelivesaudio/app/ui/navigation/StartDestination.kt` — pure start-destination selector.
- `app/src/main/java/com/ninelivesaudio/app/ui/settings/LocalGuideState.kt` — pure default-expanded helper for the local guide.
- `app/src/test/java/com/ninelivesaudio/app/domain/model/AppSettingsTest.kt`
- `app/src/test/java/com/ninelivesaudio/app/ui/navigation/StartDestinationTest.kt`
- `app/src/test/java/com/ninelivesaudio/app/ui/onboarding/OnboardingChoiceTest.kt`
- `app/src/test/java/com/ninelivesaudio/app/ui/settings/LocalGuideStateTest.kt`

**Modify:**
- `app/src/main/java/com/ninelivesaudio/app/domain/model/AppSettings.kt` — flip default + add field.
- `app/src/main/java/com/ninelivesaudio/app/service/SettingsManager.kt` — add `isLoaded` flag.
- `app/src/main/java/com/ninelivesaudio/app/ui/navigation/NineLivesNavHost.kt` — `WELCOME` route, `startDestination` param, Welcome wiring.
- `app/src/main/java/com/ninelivesaudio/app/MainActivity.kt` — gate on `isLoaded`, compute + pass start destination.
- `app/src/main/java/com/ninelivesaudio/app/ui/settings/SettingsScreen.kt` — collapsible guide in the Local Folders group.

**Verification commands (used throughout):**
- Unit tests: `./gradlew :app:testDebugUnitTest`
- Compile check (Compose/VM/Activity, no NDK): `./gradlew :app:compileDebugKotlin`

> Note: `:app:assembleDebug` pulls the `:whisper` native build, which can fail in clean/worktree checkouts. Use `compileDebugKotlin` for Kotlin compile verification instead; it does not trigger the CMake/NDK link.

---

## Task 1: Flip default to LOCAL + add onboarding flag

**Files:**
- Modify: `app/src/main/java/com/ninelivesaudio/app/domain/model/AppSettings.kt:7`
- Test: `app/src/test/java/com/ninelivesaudio/app/domain/model/AppSettingsTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/ninelivesaudio/app/domain/model/AppSettingsTest.kt`:

```kotlin
package com.ninelivesaudio.app.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppSettingsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `default mode is LOCAL`() {
        assertEquals(AppMode.LOCAL, AppSettings().appMode)
    }

    @Test
    fun `onboarding is incomplete by default`() {
        assertFalse(AppSettings().onboardingComplete)
    }

    @Test
    fun `legacy settings json without onboardingComplete decodes to false`() {
        // A payload saved before this field existed. Author mode was AUDIOBOOKSHELF.
        val legacy = """{"appMode":"AUDIOBOOKSHELF","serverUrl":"https://example.com"}"""
        val decoded = json.decodeFromString<AppSettings>(legacy)
        assertEquals(AppMode.AUDIOBOOKSHELF, decoded.appMode)
        assertFalse(decoded.onboardingComplete)
    }

    @Test
    fun `onboardingComplete round-trips through json`() {
        val original = AppSettings(onboardingComplete = true)
        val decoded = json.decodeFromString<AppSettings>(json.encodeToString(original))
        assertEquals(true, decoded.onboardingComplete)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ninelivesaudio.app.domain.model.AppSettingsTest"`
Expected: FAIL — compilation error (`onboardingComplete` unresolved) and/or `default mode is LOCAL` assertion failure (currently AUDIOBOOKSHELF).

- [ ] **Step 3: Apply the model change**

In `app/src/main/java/com/ninelivesaudio/app/domain/model/AppSettings.kt`, change line 7 and add the new field directly after it:

```kotlin
@Serializable
data class AppSettings(
    val appMode: AppMode = AppMode.LOCAL,
    val onboardingComplete: Boolean = false,
    val serverUrl: String = "",
    // ...rest unchanged...
```

(Only line 7 changes and one new line is inserted; leave every other field as-is.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ninelivesaudio.app.domain.model.AppSettingsTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ninelivesaudio/app/domain/model/AppSettings.kt \
        app/src/test/java/com/ninelivesaudio/app/domain/model/AppSettingsTest.kt
git commit -m "feat: default appMode to LOCAL and add onboardingComplete flag"
```

---

## Task 2: Pure start-destination selector

**Files:**
- Create: `app/src/main/java/com/ninelivesaudio/app/ui/navigation/StartDestination.kt`
- Test: `app/src/test/java/com/ninelivesaudio/app/ui/navigation/StartDestinationTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/ninelivesaudio/app/ui/navigation/StartDestinationTest.kt`:

```kotlin
package com.ninelivesaudio.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class StartDestinationTest {

    @Test
    fun `not onboarded starts at welcome`() {
        assertEquals(Routes.WELCOME, startDestinationFor(onboardingComplete = false))
    }

    @Test
    fun `onboarded starts at home`() {
        assertEquals(Routes.HOME, startDestinationFor(onboardingComplete = true))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ninelivesaudio.app.ui.navigation.StartDestinationTest"`
Expected: FAIL — `Routes.WELCOME` and `startDestinationFor` unresolved (compilation error).

- [ ] **Step 3: Add the route constant and selector**

In `app/src/main/java/com/ninelivesaudio/app/ui/navigation/NineLivesNavHost.kt`, add `WELCOME` to the `Routes` object (after `const val HOME = "home"`):

```kotlin
    const val WELCOME = "welcome"
```

Create `app/src/main/java/com/ninelivesaudio/app/ui/navigation/StartDestination.kt`:

```kotlin
package com.ninelivesaudio.app.ui.navigation

/**
 * Chooses the NavHost start destination from persisted onboarding state.
 * First-run users (never onboarded) see the Welcome screen; everyone else
 * goes straight Home. Kept pure so it can be unit-tested without Compose.
 */
fun startDestinationFor(onboardingComplete: Boolean): String =
    if (onboardingComplete) Routes.HOME else Routes.WELCOME
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ninelivesaudio.app.ui.navigation.StartDestinationTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ninelivesaudio/app/ui/navigation/NineLivesNavHost.kt \
        app/src/main/java/com/ninelivesaudio/app/ui/navigation/StartDestination.kt \
        app/src/test/java/com/ninelivesaudio/app/ui/navigation/StartDestinationTest.kt
git commit -m "feat: add WELCOME route and pure start-destination selector"
```

---

## Task 3: Pure onboarding-choice transform

This is the load-bearing logic for persisting the user's first-run choice. It must set `onboardingComplete = true` **unconditionally**, even when the chosen mode equals the current mode (the default is now LOCAL, so picking Local must not be a no-op — see spec "Guard caveat").

**Files:**
- Create: `app/src/main/java/com/ninelivesaudio/app/ui/onboarding/OnboardingChoice.kt`
- Test: `app/src/test/java/com/ninelivesaudio/app/ui/onboarding/OnboardingChoiceTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/ninelivesaudio/app/ui/onboarding/OnboardingChoiceTest.kt`:

```kotlin
package com.ninelivesaudio.app.ui.onboarding

import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingChoiceTest {

    @Test
    fun `choosing server sets mode and completes onboarding`() {
        val result = applyOnboardingChoice(AppSettings(), AppMode.AUDIOBOOKSHELF)
        assertEquals(AppMode.AUDIOBOOKSHELF, result.appMode)
        assertTrue(result.onboardingComplete)
    }

    @Test
    fun `choosing local completes onboarding even when mode is already local`() {
        // Default mode is LOCAL; picking Local must still flip onboardingComplete.
        val current = AppSettings(appMode = AppMode.LOCAL, onboardingComplete = false)
        val result = applyOnboardingChoice(current, AppMode.LOCAL)
        assertEquals(AppMode.LOCAL, result.appMode)
        assertTrue(result.onboardingComplete)
    }

    @Test
    fun `unrelated settings are preserved`() {
        val current = AppSettings(serverUrl = "https://example.com", volume = 0.5)
        val result = applyOnboardingChoice(current, AppMode.LOCAL)
        assertEquals("https://example.com", result.serverUrl)
        assertEquals(0.5, result.volume, 0.0)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ninelivesaudio.app.ui.onboarding.OnboardingChoiceTest"`
Expected: FAIL — `applyOnboardingChoice` unresolved (compilation error).

- [ ] **Step 3: Write the transform**

Create `app/src/main/java/com/ninelivesaudio/app/ui/onboarding/OnboardingChoice.kt`:

```kotlin
package com.ninelivesaudio.app.ui.onboarding

import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings

/**
 * Applies a first-run source choice: sets the chosen [mode] and marks onboarding
 * complete. Always sets onboardingComplete = true, even when [mode] already
 * matches the current mode, so picking the default (LOCAL) is never a no-op.
 */
fun applyOnboardingChoice(current: AppSettings, mode: AppMode): AppSettings =
    current.copy(appMode = mode, onboardingComplete = true)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ninelivesaudio.app.ui.onboarding.OnboardingChoiceTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/ninelivesaudio/app/ui/onboarding/OnboardingChoice.kt \
        app/src/test/java/com/ninelivesaudio/app/ui/onboarding/OnboardingChoiceTest.kt
git commit -m "feat: add pure onboarding-choice transform"
```

---

## Task 4: SettingsManager load gate

Adds an `isLoaded` StateFlow so `MainActivity` can wait for the async settings load before deciding the start destination (avoids a returning user briefly routing to WELCOME). Android-coupled (no cheap unit test); verified by compile + behavior.

**Files:**
- Modify: `app/src/main/java/com/ninelivesaudio/app/service/SettingsManager.kt:64-65` (add flow) and `:72-121` (set flag in `loadSettings`).

- [ ] **Step 1: Add the StateFlow**

In `SettingsManager.kt`, directly after the existing `_settings`/`settings` declarations (lines 64-65), add:

```kotlin
    private val _isLoaded = MutableStateFlow(false)
    /** Becomes true once [loadSettings] has finished its first read (success or fallback). */
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()
```

- [ ] **Step 2: Set the flag when load finishes**

In `loadSettings()`, wrap the existing `try { ... } catch (e: Exception) { ... }` body with a `finally` that flips the flag, so every path (loaded, defaults, error) marks loaded. The existing block ends at line 120 with the closing brace of the `catch`. Add immediately after it, still inside the `withContext` lambda:

```kotlin
        } catch (e: Exception) {
            Log.e(TAG, "loadSettings: Error loading settings", e)
            val defaults = AppSettings(downloadPath = defaultDownloadPath())
            _settings.value = defaults
            defaults
        } finally {
            _isLoaded.value = true
        }
```

(The `finally` does not change the value returned by the `try`/`catch` expression, so `loadSettings()` still returns the loaded/default `AppSettings`.)

- [ ] **Step 3: Compile-check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/ninelivesaudio/app/service/SettingsManager.kt
git commit -m "feat: expose SettingsManager.isLoaded gate for startup routing"
```

---

## Task 5: WelcomeViewModel

Persists the onboarding choice through `SettingsManager.updateSettings` using the pure `applyOnboardingChoice` transform from Task 3. Android-coupled; verified by compile.

**Files:**
- Create: `app/src/main/java/com/ninelivesaudio/app/ui/onboarding/WelcomeViewModel.kt`

- [ ] **Step 1: Write the ViewModel**

Create `app/src/main/java/com/ninelivesaudio/app/ui/onboarding/WelcomeViewModel.kt`:

```kotlin
package com.ninelivesaudio.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.service.SettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WelcomeViewModel @Inject constructor(
    private val settingsManager: SettingsManager,
) : ViewModel() {

    /** Persist the first-run source choice and mark onboarding complete. */
    fun choose(mode: AppMode) {
        viewModelScope.launch {
            settingsManager.updateSettings { applyOnboardingChoice(it, mode) }
        }
    }
}
```

- [ ] **Step 2: Compile-check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/ninelivesaudio/app/ui/onboarding/WelcomeViewModel.kt
git commit -m "feat: add WelcomeViewModel to persist onboarding choice"
```

---

## Task 6: Welcome screen composable

Two equal-weight cards (Local / Server), clean (icon + label only, no subtitle per the design). Uses existing theme colors and icons matching `SourceModeToggle` (`Icons.Outlined.FolderOpen`, `Icons.Outlined.Dns`).

**Files:**
- Create: `app/src/main/java/com/ninelivesaudio/app/ui/onboarding/WelcomeScreen.kt`

- [ ] **Step 1: Write the screen**

Create `app/src/main/java/com/ninelivesaudio/app/ui/onboarding/WelcomeScreen.kt`:

```kotlin
package com.ninelivesaudio.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.ui.theme.ArchiveTextMuted
import com.ninelivesaudio.app.ui.theme.ArchiveTextPrimary
import com.ninelivesaudio.app.ui.theme.ArchiveVoidSurface
import com.ninelivesaudio.app.ui.theme.GoldFilament

@Composable
fun WelcomeScreen(
    onChooseLocal: () -> Unit,
    onChooseServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Nine Lives",
            style = MaterialTheme.typography.headlineMedium,
            color = GoldFilament,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Where do your audiobooks live?",
            style = MaterialTheme.typography.bodyMedium,
            color = ArchiveTextMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SourceCard(
                icon = Icons.Outlined.FolderOpen,
                label = "Local",
                onClick = onChooseLocal,
                modifier = Modifier.weight(1f),
            )
            SourceCard(
                icon = Icons.Outlined.Dns,
                label = "Server",
                onClick = onChooseServer,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SourceCard(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ArchiveVoidSurface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 28.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = GoldFilament,
                modifier = Modifier.size(36.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = ArchiveTextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
```

> If any imported theme color (`ArchiveTextMuted`, `ArchiveTextPrimary`, `ArchiveVoidSurface`, `GoldFilament`) does not resolve, confirm its exact name in `app/src/main/java/com/ninelivesaudio/app/ui/theme/` (these names are used by `SettingsScreen.kt`) and adjust the import.
>
> `Card(onClick = ...)` uses the Material3 clickable-card overload (stable in the M3 version this project uses). If it does not resolve, replace it with a plain `Card` plus `modifier = modifier.clickable { onClick() }` (add `import androidx.compose.foundation.clickable`).

- [ ] **Step 2: Compile-check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/ninelivesaudio/app/ui/onboarding/WelcomeScreen.kt
git commit -m "feat: add first-run Welcome screen with Local/Server cards"
```

---

## Task 7: Wire Welcome into the NavHost

**Files:**
- Modify: `app/src/main/java/com/ninelivesaudio/app/ui/navigation/NineLivesNavHost.kt:37-46` (signature/start destination) and add a `composable(Routes.WELCOME)` block.

- [ ] **Step 1: Add a startDestination parameter**

Change the `NineLivesNavHost` signature and `NavHost` call so the start destination is injectable (defaulting to HOME to preserve existing callers/tests):

```kotlin
@Composable
fun NineLivesNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    startDestination: String = Routes.HOME,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
```

- [ ] **Step 2: Add the Welcome destination**

Add this `composable` block inside the `NavHost { ... }` body (e.g. immediately before `composable(Routes.HOME)`). Add the required imports at the top: `import androidx.hilt.navigation.compose.hiltViewModel`, `import com.ninelivesaudio.app.domain.model.AppMode`, `import com.ninelivesaudio.app.ui.onboarding.WelcomeScreen`, `import com.ninelivesaudio.app.ui.onboarding.WelcomeViewModel`.

```kotlin
        composable(Routes.WELCOME) {
            val welcomeViewModel: WelcomeViewModel = hiltViewModel()
            fun goToSettings() {
                navController.navigate(Routes.SETTINGS) {
                    popUpTo(Routes.WELCOME) { inclusive = true }
                }
            }
            WelcomeScreen(
                onChooseLocal = {
                    welcomeViewModel.choose(AppMode.LOCAL)
                    goToSettings()
                },
                onChooseServer = {
                    welcomeViewModel.choose(AppMode.AUDIOBOOKSHELF)
                    goToSettings()
                },
            )
        }
```

- [ ] **Step 3: Compile-check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/ninelivesaudio/app/ui/navigation/NineLivesNavHost.kt
git commit -m "feat: wire Welcome screen into navigation graph"
```

---

## Task 8: Gate MainActivity on settings load + pass start destination

`MainActivity` must wait for `settingsManager.isLoaded` before building the NavHost, then pass `startDestinationFor(appSettings.onboardingComplete)`. While loading, show only the cosmic background (avoids a wrong-route flash for returning users).

**Files:**
- Modify: `app/src/main/java/com/ninelivesaudio/app/MainActivity.kt:82-180` (the `setContent` body).

- [ ] **Step 1: Collect isLoaded**

In `MainActivity.setContent`, directly after the existing `val appSettings by settingsManager.settings.collectAsStateWithLifecycle()` (line 84), add:

```kotlin
            val settingsLoaded by settingsManager.isLoaded.collectAsStateWithLifecycle()
```

- [ ] **Step 2: Gate the content and pass the start destination**

Inside `NineLivesAudioTheme(unhingedSettings = unhingedSettings) { ... }`, wrap the existing body so it only renders once loaded. Replace the opening of the theme body (the lines that currently create `navController` and the `WhisperHost`) so it reads:

```kotlin
            NineLivesAudioTheme(unhingedSettings = unhingedSettings) {
                if (!settingsLoaded) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        CosmicBackgroundGradient()
                    }
                    return@NineLivesAudioTheme
                }

                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route
                val screenWidthDp = LocalConfiguration.current.screenWidthDp
                val useRailNavigation = screenWidthDp >= 720
                val startDestination = startDestinationFor(appSettings.onboardingComplete)
```

Then update the `NineLivesNavHost(...)` call (currently lines 156-159) to pass the start destination:

```kotlin
                                    NineLivesNavHost(
                                        navController = navController,
                                        startDestination = startDestination,
                                        modifier = Modifier.weight(1f)
                                    )
```

Add the import: `import com.ninelivesaudio.app.ui.navigation.startDestinationFor`.

> The `return@NineLivesAudioTheme` early-out keeps the existing `WhisperHost`/`Scaffold` block unchanged below it. `CosmicBackgroundGradient`, `Modifier`, and `fillMaxSize` are already imported.

- [ ] **Step 3: Compile-check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/ninelivesaudio/app/MainActivity.kt
git commit -m "feat: route first-run to Welcome after settings load"
```

---

## Task 9: Collapsible local-loading guide in Settings

Adds a "How it works" section to the Local Folders group, expanded by default when no local libraries exist yet (first-run), collapsible afterward. The default-expanded decision is a pure function (tested); the UI is compile-verified.

**Files:**
- Create: `app/src/main/java/com/ninelivesaudio/app/ui/settings/LocalGuideState.kt`
- Test: `app/src/test/java/com/ninelivesaudio/app/ui/settings/LocalGuideStateTest.kt`
- Modify: `app/src/main/java/com/ninelivesaudio/app/ui/settings/SettingsScreen.kt:140-159` (Local Folders group) and the `LocalFoldersSection` composable (around line 836).

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/ninelivesaudio/app/ui/settings/LocalGuideStateTest.kt`:

```kotlin
package com.ninelivesaudio.app.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalGuideStateTest {

    @Test
    fun `guide starts expanded when no local libraries exist`() {
        assertTrue(localGuideStartsExpanded(hasLocalLibraries = false))
    }

    @Test
    fun `guide starts collapsed once a library exists`() {
        assertFalse(localGuideStartsExpanded(hasLocalLibraries = true))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ninelivesaudio.app.ui.settings.LocalGuideStateTest"`
Expected: FAIL — `localGuideStartsExpanded` unresolved (compilation error).

- [ ] **Step 3: Write the helper**

Create `app/src/main/java/com/ninelivesaudio/app/ui/settings/LocalGuideState.kt`:

```kotlin
package com.ninelivesaudio.app.ui.settings

/**
 * The local-loading guide is shown expanded on first run (no folders added yet)
 * and collapsed once the user has at least one local library.
 */
fun localGuideStartsExpanded(hasLocalLibraries: Boolean): Boolean = !hasLocalLibraries
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.ninelivesaudio.app.ui.settings.LocalGuideStateTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Add the guide composable**

In `SettingsScreen.kt`, add this composable near `LocalFoldersSection` (e.g. directly above its declaration around line 835). Add imports at the top of the file if not already present: `import androidx.compose.animation.AnimatedVisibility`, `import androidx.compose.foundation.clickable`, `import androidx.compose.material.icons.outlined.ExpandLess`, `import androidx.compose.material.icons.outlined.ExpandMore`, `import androidx.compose.runtime.getValue`, `import androidx.compose.runtime.mutableStateOf`, `import androidx.compose.runtime.remember`, `import androidx.compose.runtime.setValue`.

```kotlin
@Composable
private fun LocalLoadingGuide(
    startExpanded: Boolean,
) {
    var expanded by remember(startExpanded) { mutableStateOf(startExpanded) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "How it works",
                style = MaterialTheme.typography.bodyMedium,
                color = ArchiveTextPrimary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = ArchiveTextMuted,
                modifier = Modifier.size(20.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Text(
                text = "Point Nine Lives at one folder that holds your audiobooks. " +
                    "Two ways to arrange what's inside:\n\n" +
                    "• A folder per book — each book gets its own folder of audio files. " +
                    "The folder name becomes the title. For multi-part books, tag the tracks " +
                    "or name them 01, 02… so they play in order. Drop a cover.jpg in for art.\n\n" +
                    "• Single files — a lone .m4b or .mp3 sitting loose in that folder becomes its own book.\n\n" +
                    "Only the top folder is read, one level down. " +
                    "Supported: m4b, m4a, mp3, opus, ogg, flac, aac, wma, wav.",
                style = MaterialTheme.typography.bodySmall,
                color = ArchiveTextMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
```

- [ ] **Step 6: Render the guide in the Local Folders group**

In the `SettingsGroup(title = "Local Folders")` block (lines 140-159), add the guide as the first child, above `LocalFoldersSection(...)`:

```kotlin
                SettingsGroup(title = "Local Folders") {
                    LocalLoadingGuide(
                        startExpanded = localGuideStartsExpanded(uiState.localLibraries.isNotEmpty().not()),
                    )
                    LocalFoldersSection(
                        // ...existing args unchanged...
```

> `localGuideStartsExpanded(uiState.localLibraries.isNotEmpty().not())` passes `hasLocalLibraries = uiState.localLibraries.isNotEmpty()`; equivalently use `localGuideStartsExpanded(hasLocalLibraries = uiState.localLibraries.isNotEmpty())`. Use the clearer named form.

So the actual line to write is:

```kotlin
                    LocalLoadingGuide(
                        startExpanded = localGuideStartsExpanded(
                            hasLocalLibraries = uiState.localLibraries.isNotEmpty(),
                        ),
                    )
```

- [ ] **Step 7: Compile-check + run the new test**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests "com.ninelivesaudio.app.ui.settings.LocalGuideStateTest"`
Expected: BUILD SUCCESSFUL; 2 tests PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/ninelivesaudio/app/ui/settings/LocalGuideState.kt \
        app/src/main/java/com/ninelivesaudio/app/ui/settings/SettingsScreen.kt \
        app/src/test/java/com/ninelivesaudio/app/ui/settings/LocalGuideStateTest.kt
git commit -m "feat: add collapsible local-loading guide to settings"
```

---

## Task 10: Full verification

- [ ] **Step 1: Run the whole unit-test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all tests pass (including the four new test classes and pre-existing tests).

- [ ] **Step 2: Full Kotlin compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual smoke (device/emulator, optional but recommended)**

Install and verify by hand:
1. Fresh install (or clear app data) → app opens on the **Welcome** screen with two equal Local/Server cards.
2. Tap **Local** → lands in Settings with the **Local Folders** group, "How it works" guide **expanded**, source = Local Files.
3. Force-quit and relaunch → opens on **Home** (onboarding not shown again).
4. Clear data, relaunch, tap **Server** → lands in Settings with the **Connection** group, source = Server.
5. Add a local folder, return to Settings → "How it works" now starts **collapsed**.

- [ ] **Step 4: Confirm the branch is clean**

Run: `git status` — expect a clean tree with all task commits present on `feat/default-to-local-onboarding`.

---

## Notes for the implementer

- **TDD scope:** Tasks 1–3 and 9 have genuine unit tests (pure logic). Tasks 4–8 are Compose/ViewModel/Activity wiring that the project does not unit-test; they are verified by `compileDebugKotlin` and the Task 10 manual smoke. This matches the existing test conventions (plain JUnit4, no Robolectric/mockk/Compose-UI unit tests in `src/test`).
- **No new persisted state beyond `onboardingComplete`.** The guide's collapse state is in-memory only (intentional).
- **Back-compat:** `Json` is configured with `ignoreUnknownKeys = true` and the new field has a default, so existing encrypted settings load without migration.
