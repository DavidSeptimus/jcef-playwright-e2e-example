# JCEF + Playwright E2E Example

A minimal IntelliJ Platform plugin that demonstrates how to **end-to-end test a JCEF (Chromium
Embedded) UI** using JetBrains' modern **IDE Starter + Driver** framework together with
**Playwright** attached over the Chromium DevTools Protocol (CDP).

The plugin itself is intentionally tiny: a **tool window** hosting a small HTML page with a button.
Clicking the button round-trips through the browser pipe to Kotlin and back to display a message
(`button click → window.__DemoTools.send → Kotlin → window.__showMessage`). The interesting part is the
**`e2e-tests/`** harness, which drives that button with a **real Playwright gesture over CDP** and then
asserts the round-trip from both ends: the page-visible message **and** that Kotlin actually observed
the click. That JS↔Kotlin round-trip is what makes this a meaningful *plugin* test — Playwright drives
a genuine click, and we verify it reached the backend and the backend's response reached the page.

> Scope: single module, **monolith** only (no split frontend/backend). The focus is the JCEF +
> Playwright testing pattern, not plugin architecture.

## Architecture

```mermaid
flowchart LR
    subgraph IDE["IntelliJ IDEA (with the plugin installed)"]
        Factory["DemoToolWindowFactory<br/>toolWindow EP"]
        Panel["DemoToolWindowPanel<br/>JCEF browser + pipe + hooks"]
        Bridge["DemoBridge<br/>app-service test seam"]
        Page["index.html<br/>button + message"]
        Factory -->|creates| Panel
        Panel -->|registers itself| Bridge
        Panel -->|loadHTML| Page
        Page -->|"window.__DemoTools.send"| Panel
        Panel -->|"window.__showMessage"| Page
    end
    subgraph E2E["e2e-tests: IDE Starter + Driver"]
        Driver["Driver @Remote"]
        Playwright["Playwright over CDP"]
    end
    Driver -->|"@Remote calls hooks"| Bridge
    Playwright -->|"real click via CDP"| Page
```

- **`DemoToolWindowFactory`** registers the tool window (the `com.intellij.toolWindow` EP). When the
  tool window is shown, it creates the panel.
- **`DemoToolWindowPanel`** owns the `JBCefBrowser`, installs the **browser pipe** (a `JBCefJSQuery`
  exposed to JS as `window.__DemoTools.send`), handles incoming messages, and pushes results back to
  the page with `window.__showMessage`. It also exposes `@TestOnly` hooks the test reads: a render
  latch (`hasRenderedForTest`) and the observed click count (`clickCountForTest`).
- **`DemoBridge`** is a tiny `@Service(APP)` **test seam**: the panel registers itself on creation, so
  the Driver `@Remote` stub can reach the live panel's hooks (a tool-window panel instance is otherwise
  hard to resolve through the platform object graph).
- **`index.html`** is the JCEF page: a button and a message element, wired to the pipe.

## How the gesture test works

```mermaid
sequenceDiagram
    participant T as e2e Test
    participant PW as Playwright (CDP)
    participant JS as index.html
    participant P as DemoToolWindowPanel (Kotlin)
    participant B as DemoBridge (Driver @Remote)

    T->>PW: clickButton()
    PW->>JS: real CDP click on the button
    JS->>P: window.__DemoTools.send(buttonClicked)
    P->>P: count++, format text
    P->>JS: window.__showMessage(text)
    Note over T,B: assert from both ends
    T->>PW: messageText()
    PW-->>T: Button clicked 1 time(s)
    T->>B: clickCountForTest()
    B-->>T: 1
```

One real gesture, asserted from both ends: Playwright reads the page-visible `#message` (proving
Kotlin's response reached the page), and the Driver reads `DemoBridge.clickCountForTest()` (proving the
click actually round-tripped to Kotlin). Driving the gesture for real is what exercises the JS click
handler + the pipe it fires — a JS-only button would tell us nothing about the plugin integration.

## Attaching Playwright to JCEF over CDP

JCEF is Chromium under the hood, so it can expose a standard DevTools Protocol endpoint. Launch the
IDE with `-Dide.browser.jcef.debug.port=<port>` and Playwright's `connectOverCDP` attaches to the
**already-running** CEF — no Playwright-managed browser is downloaded. CDP input is dispatched into
the renderer, so it works even for off-screen-rendered browsers.

```mermaid
flowchart LR
    Opt["-Dide.browser.jcef.debug.port=9222"] --> CEF["JCEF / CEF opens<br/>a CDP endpoint"]
    CEF --> Port["localhost:9222"]
    PW["Playwright.connectOverCDP"] --> Port
    PW --> Find["find page with the button"]
    Find --> Click["real mouse/keyboard via CDP"]
    Click --> Renderer["CEF renderer<br/>handles it like a user"]
```

## Running the tests

```bash
./gradlew e2eTest
```

This builds the plugin, launches a real IntelliJ IDEA with it installed, runs the two test classes,
and shuts down. The first run downloads the IDE under test (cached afterwards).

- **`DemoSmokeTest`** — opens the tool window, confirms the JCEF page renders, and (via the
  `CIServer` override) fails on any IDE-side exception.
- **`DemoGestureTest`** — drives a real Playwright click; asserts the page message and that Kotlin
  observed the click.

**Environment:** the tests launch a real IDE with a JCEF (Chromium) browser, so they need an active
desktop session. Gestures go through CDP (dispatched into the renderer, not the OS), so no special
input permissions are required. On a headless Linux CI, run under a virtual display such as `Xvfb`.

## Why the `testIdeUi` task lives in the root build

The IntelliJ Platform Gradle plugin **rejects `testIdeUi` in `.module` projects**, and the root is
where the plugin-under-test is built. So `e2e-tests/` holds only the harness + tests (with the
JUnit5 / Starter / Playwright dependencies kept off the main classpath), and the **root**
`build.gradle.kts` registers the `e2eTest` task, pulling `e2e-tests`'s compiled classes + runtime
classpath through two resolvable configurations (pure dependency resolution → configuration-cache
safe). A distinguishing attribute disambiguates them from the module's default `runtimeElements`.

## Key files

| File | Role |
|------|------|
| `src/main/resources/META-INF/plugin.xml` | Registers the `Demo` tool window (`defaultExtensionNs="com.intellij"`). |
| `src/.../DemoToolWindowFactory.kt` | The `toolWindow` EP factory. |
| `src/.../DemoToolWindowPanel.kt` | JCEF browser + browser pipe + `@TestOnly` hooks. |
| `src/.../DemoBridge.kt` | `@Service(APP)` test seam the Driver binds to. |
| `src/main/resources/demo-preview/index.html` | The JCEF page (button + message). |
| `e2e-tests/.../DemoE2ETestBase.kt` | Starter context, `CIServer` override, CDP port, launch helpers. |
| `e2e-tests/.../DemoDriverSupport.kt` | Driver `@Remote` stub for `DemoBridge`, tool-window activation. |
| `e2e-tests/.../PlaywrightPreviewSupport.kt` | `connectOverCDP` attach + gesture helpers. |
| `e2e-tests/.../Demo{Smoke,Gesture}Test.kt` | The smoke + Playwright gesture tests. |
| `build.gradle.kts` (root) | Plugin build + the root-owned `e2eTest` `testIdeUi` task. |
