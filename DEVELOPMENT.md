# Development notes

## Build app locally

### Clone with submodules

The Android NDK build depends on the `llama-rn-turbo-quant` submodule
(llama.cpp + TurboQuant CPU kernels). A plain `git clone` leaves that
directory empty and the CMake configure step fails. Clone with:

```bash
git clone --recurse-submodules https://github.com/Crane-AI-Labs/gallery.git
```

Or, if you already cloned without `--recurse-submodules`:

```bash
git submodule update --init --recursive
```

### HuggingFace OAuth (upstream gallery features only)

To successfully build and run the application through Android Studio, you need to configure it with your own HuggingFace Developer Application ([official doc](https://huggingface.co/docs/hub/oauth#creating-an-oauth-app)). This is required for the model download functionality to work correctly.

After you've created a developer application:

1. In [`ProjectConfig.kt`](https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/common/ProjectConfig.kt), replace the placeholders for `clientId` and `redirectUri` with the values from your HuggingFace developer application.

1. In [`app/build.gradle.kts`](https://github.com/google-ai-edge/gallery/blob/c1b50e160a66d5ea2ec2d8d8e63088b3cc0761bc/Android/src/app/build.gradle.kts#L41-L44), modify the `manifestPlaceholders["appAuthRedirectScheme"]` value to match the redirect URL you configured in your HuggingFace developer application.
