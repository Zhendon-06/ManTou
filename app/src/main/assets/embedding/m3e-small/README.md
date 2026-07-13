# m3e-small local embedding assets

Expected files:

- `model.onnx`
- `vocab.txt`
- `intent_vectors.json`

Generate them from the repository root:

```bash
uv venv --python 3.11 .venv-m3e
uv pip install --python .venv-m3e/bin/python -r scripts/m3e-small-requirements.txt
.venv-m3e/bin/python scripts/export_m3e_small_onnx.py
```

`intent_vectors.json` is generated from `scripts/intent_samples.json`. Run with
`--vectors-only` when only the intent corpus changed.

The app falls back to local high-precision rules and, when needed, cloud LLM
intent detection when these files are absent.
