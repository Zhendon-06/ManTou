#!/usr/bin/env python3
"""Export m3e-small and the precomputed local intent prototype index."""

import argparse
import hashlib
import json
from pathlib import Path

import onnx
import torch
from transformers import AutoModel, AutoTokenizer


ROOT = Path(__file__).resolve().parents[1]
ASSET_DIR = ROOT / "app" / "src" / "main" / "assets" / "embedding" / "m3e-small"
INTENT_SAMPLES_PATH = ROOT / "scripts" / "intent_samples.json"
INTENT_INDEX_PATH = ASSET_DIR / "intent_vectors.json"
MODEL_ID = "moka-ai/m3e-small"
MODEL_REVISION = "44c696631b2a8c200220aaaad5f987f096e986df"
MAX_LENGTH = 64
INTENT_INDEX_SCHEMA_VERSION = 1
EMBEDDING_BATCH_SIZE = 16


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


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--vectors-only",
        action="store_true",
        help="Regenerate only intent_vectors.json without exporting ONNX again.",
    )
    return parser.parse_args()


def load_intent_samples() -> tuple[dict, bytes]:
    raw = INTENT_SAMPLES_PATH.read_bytes()
    corpus = json.loads(raw)
    if corpus.get("model") != MODEL_ID:
        raise RuntimeError(f"Intent corpus model must be {MODEL_ID}")

    all_texts: list[str] = []
    for intent in ("generate_app", "chat"):
        records = corpus.get(intent)
        if not isinstance(records, list) or len(records) < 3:
            raise RuntimeError(f"Intent corpus needs at least 3 {intent} samples")
        for record in records:
            text = record.get("text", "").strip()
            category = record.get("category", "").strip()
            if not text or not category:
                raise RuntimeError(f"Invalid {intent} sample: {record}")
            all_texts.append(text)

    if len(all_texts) != len(set(all_texts)):
        raise RuntimeError("Intent corpus contains duplicate sample text")
    return corpus, raw


def embed_texts(tokenizer, model, texts: list[str]) -> list[list[float]]:
    vectors: list[list[float]] = []
    with torch.no_grad():
        for start in range(0, len(texts), EMBEDDING_BATCH_SIZE):
            batch = texts[start : start + EMBEDDING_BATCH_SIZE]
            encoded = tokenizer(
                [text.lower() for text in batch],
                max_length=MAX_LENGTH,
                padding="max_length",
                truncation=True,
                return_tensors="pt",
            )
            hidden = model(**encoded, return_dict=True).last_hidden_state
            mask = encoded["attention_mask"].unsqueeze(-1).to(hidden.dtype)
            pooled = (hidden * mask).sum(dim=1) / mask.sum(dim=1).clamp(min=1)
            normalized = torch.nn.functional.normalize(pooled, p=2, dim=1)
            vectors.extend(normalized.cpu().tolist())
    return vectors


def export_intent_index(tokenizer, model) -> None:
    corpus, corpus_raw = load_intent_samples()
    index = {
        "schema_version": INTENT_INDEX_SCHEMA_VERSION,
        "corpus_version": corpus["version"],
        "corpus_sha256": hashlib.sha256(corpus_raw).hexdigest(),
        "model": MODEL_ID,
        "model_revision": MODEL_REVISION,
        "dimension": model.config.hidden_size,
    }

    for intent in ("generate_app", "chat"):
        records = corpus[intent]
        vectors = embed_texts(tokenizer, model, [record["text"] for record in records])
        index[intent] = [
            {
                "category": record["category"],
                "text": record["text"],
                "vector": [round(value, 8) for value in vector],
            }
            for record, vector in zip(records, vectors, strict=True)
        ]

    INTENT_INDEX_PATH.write_text(
        json.dumps(index, ensure_ascii=False, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
    print(
        f"Wrote {INTENT_INDEX_PATH} "
        f"({len(index['generate_app'])} generate_app, {len(index['chat'])} chat)"
    )


def export_onnx(tokenizer, model) -> None:

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


def main() -> None:
    args = parse_args()
    ASSET_DIR.mkdir(parents=True, exist_ok=True)

    tokenizer = AutoTokenizer.from_pretrained(MODEL_ID, revision=MODEL_REVISION)
    model = AutoModel.from_pretrained(MODEL_ID, revision=MODEL_REVISION)
    model.eval()

    if not args.vectors_only:
        export_onnx(tokenizer, model)
    export_intent_index(tokenizer, model)


if __name__ == "__main__":
    main()
