---
license: other
license_name: medgemma-terms-of-use
license_link: https://ai.google.dev/gemma/terms
base_model: google/medgemma-1.5-4b-it
tags:
  - medgemma
  - gguf
  - q4_k_m
  - medical
  - clinical-decision-support
  - uganda-clinical-guidelines
  - qlora
  - unsloth
language:
  - en
pipeline_tag: text-generation
---

# V2 MedGemma Q4_K_M GGUF

Quantized GGUF (Q4_K_M) of [Cal3bd3v/V2-medGemma-sft](https://huggingface.co/Cal3bd3v/V2-medGemma-sft) — a fine-tuned MedGemma 1.5 4B for clinical decision support based on the Uganda Clinical Guidelines.

## Model Details

- **Base model:** `google/medgemma-1.5-4b-it`
- **Fine-tuning:** QLoRA (4-bit, rank 64) with Unsloth + SFTTrainer
- **Dataset:** 11,691 training pairs across 7 clinical decision-support categories
- **Quantization:** Q4_K_M (2.4 GB)
- **Format:** GGUF (llama.cpp compatible)

## What This Model Does

This model is a **clinical thinking aid** scoped to the Uganda Clinical Guidelines. It helps with:

- Differential diagnosis reasoning
- Danger sign identification
- Referral criteria and escalation triggers
- Investigation recommendations
- Clinical assessment guidance

It **refuses** treatment and dosing questions, redirecting to facility guidelines.

## System Prompt

```
You are a clinical thinking aid based on the Uganda Clinical Guidelines.
Help identify danger signs, guide differential reasoning, and support
referral decisions. Do not provide treatment or dosing recommendations.
```

## Evaluation Results (V2 Rubric, 210 samples)

| Category | Baseline | Fine-tuned | Change |
|---|---|---|---|
| diagnosis | 3.84 | **4.43** | +0.59 |
| danger_sign | 3.62 | **4.28** | +0.66 |
| differential | 2.14 | **3.77** | +1.63 |
| investigation | 3.66 | 3.03 | -0.63 |
| referral | 2.69 | 2.49 | -0.20 |
| refusal | 2.20 | **5.00** | +2.80 |
| special_populations | 1.50 | 1.61 | +0.11 |
| **Mean** | **2.91** | **3.53** | **+0.62** |

## Training Config

| Parameter | Value |
|---|---|
| LoRA rank | 64 |
| LoRA alpha | 64 |
| Epochs | 2 |
| Learning rate | 2e-4 |
| LR scheduler | cosine |
| Effective batch size | 16 |
| Optimizer | adamw_8bit |
| Final train loss | 0.4380 |

## Usage

### llama.cpp

```bash
llama-cli -m v2-medgemma-Q4_K_M.gguf -p "You are a clinical thinking aid based on the Uganda Clinical Guidelines. Help identify danger signs, guide differential reasoning, and support referral decisions. Do not provide treatment or dosing recommendations.\n\nWhat danger signs should you watch for in a patient with severe malaria?" -n 512
```

### Ollama

```bash
ollama create medgemma-uganda -f Modelfile
ollama run medgemma-uganda
```

## Limitations

- **Not a diagnostic tool.** Does not provide diagnoses, prescriptions, or automated clinical decisions.
- **Special populations** (pediatric, pregnancy) category remains weak (1.61/5).
- **Investigation** and **referral** categories showed slight regression vs baseline.
- Quantization from 4-bit LoRA merge may introduce minor rounding differences.

## Links

- **Full-precision model:** [Cal3bd3v/V2-medGemma-sft](https://huggingface.co/Cal3bd3v/V2-medGemma-sft)
- **Training repo:** [Crane-AI-Labs/Crails-Health-AI](https://github.com/Crane-AI-Labs/Crails-Health-AI)
