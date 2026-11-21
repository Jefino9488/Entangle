# Optional local GGUF inference

The Rust crate `entangle-inference` implements a real local-generation backend with `llama-cpp-2 0.1.161` and its matching `llama-cpp-sys-2`/llama.cpp source. These bindings are MIT OR Apache-2.0; the embedded llama.cpp runtime is MIT. No custom inference engine is implemented.

The Android boundary exports:

- `validateLocalModel(modelPath)` returns a small record with filename, file size and GGUF version. It checks the header/size before model selection; full tensor/chat-template compatibility is checked when loading.
- `generateLocalAnswer(modelPath, question, evidenceText, maxTokens)` generates a concise answer from ranked numbered local source passages.
- `cancelLocalGeneration()` signals the single active generation. Model loading must complete before cancellation can be observed; prompt and output decode loops check it between bounded batches/tokens.

All calls are made from Kotlin IO coroutines. The adapter holds no model between requests, allowing the reader to reclaim memory after inference. CPU threads are capped at four. Models use a 2,048-token context and 128-token prompt batches, with output capped at 256 tokens and execution capped at two minutes. The most relevant evidence passages must be first: if the prompt exceeds the context budget, its evidence tail is reduced while preserving the question and grounding instructions.

The prompt treats evidence as quoted source material, requests source-specific conflicts, and prohibits unsupported facts. Llama.cpp's grammar-constrained sampler requires a structured answer with citation IDs chosen from the actual supplied passages. The adapter parses that output and rejects incomplete output or invented source IDs. This validates source identity rather than verifying every generated claim; the UI retains exact retrieved sources separately. Deterministic evidence-only answers remain useful without a model.

## Build compatibility

The native crate detects Android and uses the configured NDK's CMake toolchain. The application builds static C++ support, so a separate `libc++_shared.so` is not required. Default crate features are disabled to avoid OpenMP and unrelated native dependencies. GPU features and llama.cpp's curl support are not enabled.

The Android build script bounds compiler concurrency to four, sets the native library profile to Release, and disables AVX assumptions for portable x86_64 emulator binaries. The arm64 build uses the portable armv8-a baseline. Faster architecture-specific kernels can be evaluated separately without changing the Kotlin/core API.

`llama-cpp-sys-2` requires CMake, a C/C++ compiler and libclang on the development host. Android compilation uses NDK 28.2.13676358. The runtime never performs network access. Model import remains user initiated through SAF.

## Validation

The crate includes unit checks for invalid files, GGUF version/file-size admission, and rejection of requests without evidence. An ignored integration test requires `ENTANGLE_TEST_MODEL` pointing to a genuine local instruction GGUF; it actually generates an answer and checks that the answer uses the supplied source.

The Qwen2.5-0.5B-Instruct Q4_K_M candidate and revision are recorded in `models/README.md`. Actual host/Android build and generation results are recorded in the project build-results document when the coordinated MVP verification completes. No synthetic model responses or model weights are shipped as application examples.
