"""Train and export an INT8 quantized TFLite NSFW + toxicity text moderation model.

Uses genuine HuggingFace datasets (ifmain/text-moderation-01,
textdetox/multilingual_toxicity_dataset, ResplendentAI/Luna_NSFW_Text, and
databricks/databricks-dolly-15k benign instructions) and initializes WordPiece
token embeddings from gravitee-io/bert-tiny-toxicity.

Outputs:
  - app/src/main/assets/moderation/text_moderation_int8.tflite
  - app/src/main/assets/moderation/vocab.txt
"""

from __future__ import annotations

import os
import shutil
from pathlib import Path

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

import numpy as np
import tensorflow as tf
import torch
from datasets import load_dataset
from sklearn.metrics import classification_report, roc_auc_score
from sklearn.model_selection import train_test_split
from transformers import AutoModel, AutoTokenizer

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "app" / "src" / "main" / "assets" / "moderation"
MODEL_NAME = "gravitee-io/bert-tiny-toxicity"
MAX_SEQ_LEN = 128
EMBED_DIM = 64
SEED = 42


def load_corpora() -> tuple[list[str], list[int], list[str]]:
    texts: list[str] = []
    labels: list[int] = []
    sources: list[str] = []

    print("Loading ifmain/text-moderation-01...")
    mod_ds = load_dataset("ifmain/text-moderation-01", split="train")
    for row in mod_ds:
        txt = (row["text"] or "").strip()
        if not txt:
            continue
        res = row["result"]
        cats = res["categories"]
        is_sexual = bool(cats.get("sexual") or cats.get("sexual-minors"))
        is_unsafe = bool(res["flagged"]) or any(bool(v) for v in cats.values())
        if is_sexual:
            # Upsample sexual examples 2x so NSFW signal is well balanced against harassment.
            for _ in range(2):
                texts.append(txt)
                labels.append(1)
                sources.append("mod_sexual")
        else:
            texts.append(txt)
            labels.append(1 if is_unsafe else 0)
            sources.append("mod_toxic" if is_unsafe else "mod_safe")

    print("Loading textdetox/multilingual_toxicity_dataset (en)...")
    detox_ds = load_dataset("textdetox/multilingual_toxicity_dataset", split="en")
    for row in detox_ds:
        txt = (row["text"] or "").strip()
        if not txt:
            continue
        y = int(row["toxic"])
        texts.append(txt)
        labels.append(y)
        sources.append("detox_toxic" if y == 1 else "detox_safe")

    print("Loading ResplendentAI/Luna_NSFW_Text...")
    nsfw_ds = load_dataset("ResplendentAI/Luna_NSFW_Text", split="train")
    for row in nsfw_ds:
        txt = (row["text"] or "").strip()
        if not txt:
            continue
        texts.append(txt)
        labels.append(1)
        sources.append("luna_nsfw")

    print("Loading databricks/databricks-dolly-15k (safe prompts/responses)...")
    dolly_ds = load_dataset("databricks/databricks-dolly-15k", split="train")
    for i, row in enumerate(dolly_ds):
        if i >= 6000:
            break
        instr = (row["instruction"] or "").strip()
        if instr:
            texts.append(instr)
            labels.append(0)
            sources.append("dolly_safe")

    print(f"Total samples: {len(texts)} (unsafe={sum(labels)}, safe={len(labels) - sum(labels)})")
    return texts, labels, sources


def build_init_embeddings(target_dim: int) -> np.ndarray:
    print(f"Extracting WordPiece embeddings from {MODEL_NAME}...")
    base = AutoModel.from_pretrained(MODEL_NAME)
    weights = base.embeddings.word_embeddings.weight.detach().cpu().numpy()  # (30522, 128)
    # Deterministic truncated SVD projection from 128 -> target_dim
    centered = weights - weights.mean(axis=0, keepdims=True)
    _, _, vt = np.linalg.svd(centered, full_matrices=False)
    proj = centered @ vt[:target_dim].T
    # Normalize variance for stable CNN training
    proj = proj / (np.std(proj) + 1e-6) * 0.05
    return proj.astype(np.float32)


def build_model(vocab_size: int, init_emb: np.ndarray, batch_size: int | None = None) -> tf.keras.Model:
    inputs = tf.keras.Input(shape=(MAX_SEQ_LEN,), batch_size=batch_size, dtype=tf.int32, name="input_ids")
    x = tf.keras.layers.Embedding(
        input_dim=vocab_size,
        output_dim=EMBED_DIM,
        embeddings_initializer=tf.keras.initializers.Constant(init_emb),
        name="wordpiece_embedding",
    )(inputs)
    x = tf.keras.layers.SpatialDropout1D(0.15)(x)

    branches = []
    for k in (1, 2, 3, 4):
        conv = tf.keras.layers.Conv1D(
            filters=64,
            kernel_size=k,
            padding="same",
            activation="relu",
            name=f"conv_k{k}",
        )(x)
        max_pool = tf.keras.layers.GlobalMaxPooling1D(name=f"max_pool_k{k}")(conv)
        avg_pool = tf.keras.layers.GlobalAveragePooling1D(name=f"avg_pool_k{k}")(conv)
        branches.extend([max_pool, avg_pool])

    h = tf.keras.layers.Concatenate(name="concat_features")(branches)
    h = tf.keras.layers.Dropout(0.3)(h)
    h = tf.keras.layers.Dense(128, activation="relu", name="mlp_hidden")(h)
    h = tf.keras.layers.Dropout(0.2)(h)
    outputs = tf.keras.layers.Dense(1, activation="sigmoid", name="unsafe_prob")(h)
    return tf.keras.Model(inputs=inputs, outputs=outputs, name="text_moderation_cnn")


def main() -> None:
    np.random.seed(SEED)
    tf.random.set_seed(SEED)
    torch.manual_seed(SEED)

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME)
    saved_files = tokenizer.save_vocabulary(str(OUT_DIR))
    vocab_path = OUT_DIR / "vocab.txt"
    if saved_files:
        saved_path = Path(saved_files[0])
        if saved_path != vocab_path:
            if vocab_path.exists():
                vocab_path.unlink()
            shutil.move(str(saved_path), str(vocab_path))
    print(f"Saved WordPiece vocab to {vocab_path} (size={tokenizer.vocab_size})")

    texts, labels, sources = load_corpora()
    indices = np.arange(len(texts))
    train_idx, val_idx = train_test_split(
        indices, test_size=0.10, random_state=SEED, stratify=sources
    )

    print("Tokenizing training and validation splits...")
    train_texts = [texts[i] for i in train_idx]
    val_texts = [texts[i] for i in val_idx]
    y_train = np.array([labels[i] for i in train_idx], dtype=np.float32)
    y_val = np.array([labels[i] for i in val_idx], dtype=np.float32)
    val_sources = [sources[i] for i in val_idx]

    x_train = tokenizer(
        train_texts,
        padding="max_length",
        truncation=True,
        max_length=MAX_SEQ_LEN,
        return_tensors="np",
    )["input_ids"].astype(np.int32)

    x_val = tokenizer(
        val_texts,
        padding="max_length",
        truncation=True,
        max_length=MAX_SEQ_LEN,
        return_tensors="np",
    )["input_ids"].astype(np.int32)

    init_emb = build_init_embeddings(EMBED_DIM)
    model = build_model(tokenizer.vocab_size, init_emb)
    model.summary()

    pos_count = float(np.sum(y_train == 1))
    neg_count = float(np.sum(y_train == 0))
    pos_weight = min(2.0, neg_count / max(pos_count, 1.0))
    class_weight = {0: 1.0, 1: pos_weight}
    print(f"Using class weights: {class_weight}")

    model.compile(
        optimizer=tf.keras.optimizers.AdamW(learning_rate=6e-4, weight_decay=5e-4),
        loss="binary_crossentropy",
        metrics=["accuracy", tf.keras.metrics.AUC(name="auc")],
    )

    callbacks = [
        tf.keras.callbacks.EarlyStopping(
            monitor="val_auc", mode="max", patience=2, restore_best_weights=True
        )
    ]

    model.fit(
        x_train,
        y_train,
        validation_data=(x_val, y_val),
        epochs=6,
        batch_size=128,
        class_weight=class_weight,
        callbacks=callbacks,
        verbose=2,
    )

    # Evaluate FP32 Keras model on held-out split
    val_preds = model.predict(x_val, batch_size=256, verbose=0).reshape(-1)
    print("\n=== Held-out FP32 Evaluation ===")
    print(f"ROC-AUC: {roc_auc_score(y_val, val_preds):.4f}")
    print(classification_report(y_val, (val_preds > 0.5).astype(int), digits=4))

    # Convert to pure TFLITE_BUILTINS INT8 quantized .tflite
    # Build representative dataset with shape (1, MAX_SEQ_LEN) int32
    rep_indices = np.random.choice(len(x_train), size=min(500, len(x_train)), replace=False)

    def representative_dataset():
        for idx in rep_indices:
            yield [x_train[idx : idx + 1]]

    # Clone with static batch_size=1 for clean variable freezing and INT8 TFLite conversion.
    infer_model = build_model(tokenizer.vocab_size, init_emb, batch_size=1)
    infer_model.set_weights(model.get_weights())

    converter = tf.lite.TFLiteConverter.from_keras_model(infer_model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.representative_dataset = representative_dataset
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    converter.inference_output_type = tf.int8

    tflite_bytes = converter.convert()
    tflite_path = OUT_DIR / "text_moderation_int8.tflite"
    tflite_path.write_bytes(tflite_bytes)
    print(f"\nExported INT8 TFLite model to {tflite_path} ({len(tflite_bytes):,} bytes)")

    # Verify ops and tensor quantization
    interpreter = tf.lite.Interpreter(model_path=str(tflite_path))
    interpreter.allocate_tensors()

    ops = interpreter._get_ops_details()
    op_names = sorted({op["op_name"] for op in ops})
    print("TFLite op names:", op_names)
    flex_ops = [op for op in op_names if op.startswith("Flex")]
    if flex_ops:
        raise RuntimeError(f"Unexpected Flex/SELECT_TF_OPS ops found: {flex_ops}")

    tensor_details = interpreter.get_tensor_details()
    int8_tensors = [t for t in tensor_details if t["dtype"] == np.int8]
    print(f"Total tensors: {len(tensor_details)}, INT8 quantized tensors: {len(int8_tensors)}")
    if not int8_tensors:
        raise RuntimeError("No INT8 quantized tensors found in exported TFLite model!")

    in_det = interpreter.get_input_details()[0]
    out_det = interpreter.get_output_details()[0]
    print("Input details:", in_det["name"], in_det["shape"], in_det["dtype"])
    print("Output details:", out_det["name"], out_det["shape"], out_det["dtype"], out_det["quantization"])

    # Evaluate the ACTUAL INT8 TFLite interpreter on held-out subset (up to 3000 balanced samples)
    scale, zero_point = out_det["quantization"]
    eval_n = min(3000, len(x_val))
    rng = np.random.default_rng(SEED)
    eval_indices = rng.choice(len(x_val), size=eval_n, replace=False)

    tflite_probs = []
    for idx in eval_indices:
        interpreter.set_tensor(in_det["index"], x_val[idx : idx + 1])
        interpreter.invoke()
        raw_out = interpreter.get_tensor(out_det["index"])[0][0]
        prob = (float(raw_out) - float(zero_point)) * float(scale) if out_det["dtype"] in (np.int8, np.uint8) else float(raw_out)
        tflite_probs.append(prob)

    tflite_probs_arr = np.array(tflite_probs)
    y_eval = y_val[eval_indices]
    eval_sources = [val_sources[i] for i in eval_indices]

    print("\n=== Held-out INT8 TFLite Evaluation ===")
    print(f"ROC-AUC: {roc_auc_score(y_eval, tflite_probs_arr):.4f}")
    print(f"Accuracy (@0.50): {np.mean((tflite_probs_arr > 0.50) == y_eval):.4f}")
    print(f"Accuracy (@0.85): {np.mean((tflite_probs_arr > 0.85) == y_eval):.4f}")

    for src in sorted(set(eval_sources)):
        mask = np.array([s == src for s in eval_sources])
        if np.sum(mask) == 0:
            continue
        acc50 = np.mean((tflite_probs_arr[mask] > 0.50) == y_eval[mask])
        acc85 = np.mean((tflite_probs_arr[mask] > 0.85) == y_eval[mask])
        mean_p = np.mean(tflite_probs_arr[mask])
        print(f"  {src:14s} (n={np.sum(mask):4d}): mean_prob={mean_p:.3f}, acc@0.50={acc50:.3f}, acc@0.85={acc85:.3f}")


if __name__ == "__main__":
    main()
