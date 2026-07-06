#!/usr/bin/env python3
"""Download moka-ai/m3e-small and export it to an Android-friendly ONNX file."""

from pathlib import Path

import onnx
import torch
from transformers import AutoModel, AutoTokenizer


ROOT = Path(__file__).resolve().parents[1]
ASSET_DIR = ROOT / "app" / "src" / "main" / "assets" / "embedding" / "m3e-small"
MODEL_ID = "moka-ai/m3e-small"
MAX_LENGTH = 64


class M3EEmbedding(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, input_ids, attention_mask, token_type_ids):
        outputs = self.model(
            input_ids=input_ids,
            attention_mask=attention_mask,
            token_type_ids=token_type_ids,
            return_dict=True,
        )
        return outputs.last_hidden_state


def main() -> None:
    ASSET_DIR.mkdir(parents=True, exist_ok=True)

    tokenizer = AutoTokenizer.from_pretrained(MODEL_ID)
    model = AutoModel.from_pretrained(MODEL_ID)
    model.eval()

    sample = tokenizer(
        "帮我生成一个番茄钟网页应用",
        max_length=MAX_LENGTH,
        padding="max_length",
        truncation=True,
        return_tensors="pt",
    )

    wrapper = M3EEmbedding(model)
    output_path = ASSET_DIR / "model.onnx"
    torch.onnx.export(
        wrapper,
        (
            sample["input_ids"],
            sample["attention_mask"],
            sample["token_type_ids"],
        ),
        output_path.as_posix(),
        input_names=["input_ids", "attention_mask", "token_type_ids"],
        output_names=["last_hidden_state"],
        dynamic_axes={
            "input_ids": {0: "batch"},
            "attention_mask": {0: "batch"},
            "token_type_ids": {0: "batch"},
            "last_hidden_state": {0: "batch"},
        },
        opset_version=17,
    )
    onnx.checker.check_model(output_path.as_posix())

    tokenizer.save_pretrained(ASSET_DIR)
    vocab_path = ASSET_DIR / "vocab.txt"
    if not vocab_path.exists():
        raise RuntimeError(f"Tokenizer did not write {vocab_path}")

    print(f"Wrote {output_path}")
    print(f"Wrote {vocab_path}")


if __name__ == "__main__":
    main()
