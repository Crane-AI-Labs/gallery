# Health Demo App – Project Refactoring & Strategic Pivot Summary
**Prepared by:** Glorry Sibomana
**Project Date:** February 2026
**Application:** Health Demo (Google AI Edge Gallery)

---

## 1. Executive Summary & Strategic Pivot
This document summarizes the comprehensive work done on the Health Demo application. Initially, the objective was to transition the application from relying on resource-intensive, on-device AI models to efficient, network-based OpenAI-compatible API endpoints for conversational reasoning (MedGemma) and voice transcription (ASR). 

**Strategic Pivot:** Due to compute scarcity and the current unavailability of the necessary LiteRT models, relying on live APIs has proven challenging for long-term scalability. Therefore, the architectural strategy is now pivoting: I am currently building a **Graph Database** solution that will replace these API dependencies. 

Even though the API implementations will be replaced, the foundational UI structurings, error handling, state management, and voice processing capabilities developed during this period remain vital. This document preserves the record of that developmental back-and-forth and the solutions implemented.

---

## 2. API Integration Phase (Legacy Work)

### 2.1 Transition to Network-Based AI Models
*   **API Client Implementation:** Established a custom `OpenAICompatibleClient` to handle asynchronous HTTP requests, temporarily replacing local model execution logic.
*   **Dynamic Configuration:** Introduced an `ApiSettingsScreen` allowing configuration of custom API endpoints, API keys, and System Prompts dynamically via `DataStore`.
*   **Model Integration:** Integrated MedGemma (`medgemma`) for medical guidance and MedASR (`medasr`) for translating audio into text.

### 2.2 Network & Connectivity Fixes
*   **Cleartext Traffic:** Updated `AndroidManifest.xml` with `android:usesCleartextTraffic="true"` to support non-HTTPS server endpoints.
*   **Endpoint Resolution:** Addressed HTTP 405 (Method Not Allowed) and HTTP 404 (Not Found) errors by sanitizing URLs, correcting request verbs, and utilizing exact model namespaces expected by external servers.

---

## 3. Core Feature Enhancements (Retained)

### 3.1 Voice Input Integration (ASR)
*   **UI Implementation:** Activated the voice recording interface within the `EnterSymptomsScreen`.
*   **Audio Capture:** Integrated Android's native `MediaRecorder` to capture high-quality audio (`.3gp`) directly from the device microphone.
*   **Permission Handling:** Implemented runtime permission requests (`RECORD_AUDIO`) with graceful fallbacks and user-facing Android Toasts.
*   **Ongoing Utility:** The audio capture UI and permission logic will be retained; the captured audio will eventually be processed locally or via the new architecture rather than the MedASR API.

### 3.2 Robust JSON Parsing & Validation
*   **Markdown Extraction:** Upgraded `ApiResponseValidator.kt` to securely parse JSON even when wrapped in Markdown code blocks (````json ... ````).
*   **Fallback Mechanisms:** Implemented a robust fallback parser capable of converting plain-text conversational responses into structured `HealthGuidance` data objects.
*   **Ongoing Utility:** These rigid parsing rules and fallbacks will be crucial when interpreting simulated responses or data returned from the upcoming Graph Database.

---

## 4. UI/UX & State Management Improvements (Retained)

### 4.1 Keyboard Responsiveness
*   **Dynamic Padding:** Resolved an issue where the Android soft keyboard would obscure text inputs and action buttons by utilizing Jetpack Compose's `imePadding()` and `navigationBarsPadding()`.
*   **Impact:** Text fields and bottom action bars in `EnterSymptomsScreen` and `ApiSettingsScreen` now intelligently slide above the keyboard, mimicking native messaging apps like WhatsApp.

### 4.2 State Lifecycle & Rendering
*   **Session Reset:** Fixed a bug where returning to the start of the app retained old data by explicitly invoking `viewModel.resetAssessment()` when tapping "Start New Assessment".
*   **Dynamic UI Sections:** Improved the `GuidanceScreen` to conditionally hide empty "Suggested Next Step" sections when no actionable follow-ups exist.

---

## 5. Next Steps: Graph Database Integration
While the API-driven approach successfully demonstrated advanced capabilities like voice-to-text and MedGemma reasoning, the compute limitations require a more sustainable backend. The focus now shifts entirely to architecting and integrating the **Graph Database**. This new data store will serve as the engine for the Health Demo, ensuring reliable, offline-capable, and compute-efficient medical guidance moving forward.
