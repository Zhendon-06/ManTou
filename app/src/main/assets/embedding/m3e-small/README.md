# m3e-small local embedding assets

Expected files:

- `model.onnx`
- `vocab.txt`

Generate them from the repository root:

```bash
uv venv --python 3.11 .venv-m3e
uv pip install --python .venv-m3e/bin/python -r scripts/m3e-small-requirements.txt
.venv-m3e/bin/python scripts/export_m3e_small_onnx.py
```

The app falls back to cloud LLM intent detection when these files are absent.
