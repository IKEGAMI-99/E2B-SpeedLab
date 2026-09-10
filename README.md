# E2B SpeedLab

A deliberately narrow Android app for pushing **Gemma 4 E2B** as fast as possible on-device.

## Target stack

- Gemma 4 E2B (`gemma-4-E2B-it.litertlm`)
- LiteRT-LM Android
- GPU backend only
- MTP / speculative decoding always enabled
- Text-only inference
- Small KV cache by default (`2048` tokens)
- Greedy decoding (`topK=1`, `temperature=0`) to favor repeatable measurements and high MTP acceptance
- Lightweight classic Android Views instead of Compose

## Current LiteRT-LM dependency

`com.google.ai.edge.litertlm:litertlm-android:0.17.0-alpha1`

The Android artifact is intentionally pinned. SpeedLab is a benchmark project, so moving to a newer runtime should be done deliberately and benchmarked before/after.

## Model

Use the MTP-capable model from the LiteRT community:

`litert-community/gemma-4-E2B-it-litert-lm / gemma-4-E2B-it.litertlm`

The app uses Android's document picker, then copies the model into private app storage so LiteRT-LM receives a real filesystem path. The expected current model size is approximately 2.588 GB.

## Modes

### Chat

Loads one persistent GPU engine and streams text. UI updates are batched so rendering does not fight inference for resources.

### Native benchmark

Uses LiteRT-LM's own benchmark API, so the displayed values are native metrics rather than character-count estimates:

- Prefill tok/s
- Decode tok/s
- TTFT
- Prefill token count
- Decode token count
- Init time

Default benchmark is 256 prefill + 256 decode tokens.

## Build

The GitHub Actions workflow builds a debug APK on every push. It uses JDK 21, Gradle 9.6.0, AGP 9.4.0 and Kotlin 2.4.20.

## Design rule

If a feature does not help measure or increase E2B inference speed, it probably does not belong here.
