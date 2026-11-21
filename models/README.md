# Local model support

Entangle's assistant works from retrieved local document evidence without model weights. For a generated explanation, import an instruction-tuned GGUF through the Android document picker. The selected model is copied to private device storage; inference uses the packaged CPU llama.cpp adapter and never calls an external inference API.

No model is bundled in the APK or committed to this repository. A runtime license and a model-weight license are separate.

## Supported resource envelope

- GGUF versions 2 and 3, with an embedded chat template supported by llama.cpp.
- Quantized files up to **512 MiB**, with at most **800 million parameters**.
- CPU inference with at most four threads, a 2,048-token context, 128-token prompt batches and at most 256 generated tokens.
- One active generation at a time. Cancel is checked between prompt batches and output tokens. Requests have a two-minute limit.
- Mmap is enabled; mlock, GPU offload, network downloads and OpenMP are disabled. The model, KV cache, context and backend are released on completion, cancellation or error.

The file-size envelope is conservative for the Redmi K50i's approximately 1.5 GB available RAM. It is not a guarantee for every custom GGUF architecture. Generation may be slower on portable baseline CPU kernels; document retrieval remains available when a model cannot be loaded.

## Tested model candidate

`Qwen/Qwen2.5-0.5B-Instruct-GGUF`, revision `9217f5db79a29953eb74d5343926648285ec7e67`, file `qwen2.5-0.5b-instruct-q4_k_m.gguf` is an Apache-2.0 instruction model within the envelope (approximately 469 MiB). Its embedded chat template is used; the application does not hardcode its tokenizer or prompt format.

Model source: https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/tree/9217f5db79a29953eb74d5343926648285ec7e67

SHA-256 of the tested GGUF: `74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db`.

This model is a testable option, not a required dependency. Users can import another compatible model. Model output is presented with the retrieved source passages; citations and conflict handling are requested in the prompt, and generated prose still requires source review.

For developer smoke tests using an already downloaded model:

```bash
cd rust
ENTANGLE_TEST_MODEL=/absolute/path/to/model.gguf cargo test -p entangle-inference real_local_model_generates_from_evidence -- --ignored --nocapture
```

The ignored model test never downloads weights automatically. Keep development weights under an ignored directory or outside the repository. See `docs/local-inference.md` for backend details and validation results.
