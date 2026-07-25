# Household Budget Bot — Phase 4: In-House Classification

_A research plan · Replace an LLM classifier with a locally-trained, locally-served model_

---

## Framing

Phases 0–3 are engineering plans: the outcome is known, the work is building it. This phase is different. Whether a small linear model matches an LLM on this task, whether char n-grams handle mixed-language input, whether sklearn-trained weights produce identical predictions inside a JVM — these are empirical questions. The work is answering them.

So this document is structured as hypotheses with pre-registered success criteria and the experiments that test them, not as iterations with definitions of done.

**Two things are learned at once**, and they are separable: whether a conventional model is *good enough* (modelling), and how to *serve* one from Ktor (engineering). Either can succeed while the other fails. Evaluate each independently.

### Why this is worth doing

Not cost — at household volume the LLM costs pennies per month, and no experiment here changes that. The returns:

- **Latency.** Removes a network round-trip from the path.
- **Determinism.** A classifier unit-testable with exact expected outputs.
- **Independence.** Survives an OpenRouter outage.
- **Transferable skill.** "Train in Python, serve in a JVM service" is a standard production pattern not yet practised here.

State this plainly, because it sets the decision rules: **if the local model merely ties the LLM, that is a success.**

---

## Prerequisite: This Phase Has a Contingent Subject

Phase 3's two-tier routing means deterministic commands (`open`, explicit list commands) are keyword-matched and never reach a classifier. Only genuinely ambiguous natural language does — and only if **phase 3 iteration 2 (natural-language routing) is actually built**, which is itself contingent on explicit command syntax proving annoying in practice.

Three consequences, all of which must be resolved before this phase starts:

1. **If phase 3 iteration 2 is never built, this phase has no subject.** There is no classifier to replace. That is a legitimate outcome, and the right response is to reconsider the target (below), not to build a classifier so that one exists to replace.
2. **The training population is the tier-2 subset, not all messages.** Keyword-matched commands produce no `(text, intent)` pairs by design. Volume projections must be computed against messages that reach the default, not total traffic.
3. **The task is three classes, not five.** `EXPENSE`, `LIST_ADD`, `OTHER`. `open` and list-show are keyword-matched. Fewer classes is a materially easier learning problem and eases the rare-class scarcity below.

### An alternative target, worth weighing first

There is a second in-house model available, and by every data-availability measure it is better supported: **the category classifier.**

`categorization_event` records `predicted_category_id`, `final_category_id`, and `was_corrected` on every confirmed expense. That is a supervised dataset with **genuine human labels** — the member either accepted the prediction or corrected it — accumulating on every expense rather than on the ambiguous subset of messages. No silver-label circularity, no synthetic augmentation required, and it grows with normal use.

The trade-offs: more classes (a dozen-plus budget lines, versus three intents), and the correction signal is only present where the confirmation card is used. But the label quality is far higher, and the serving experiments (Family C) are **identical** either way — which is where most of the stated learning value sits.

**Decide the target before labelling anything.** The gold set is the expensive, irreversible commitment, and it is target-specific. If intent routing is never built, category classification is the phase.

The remainder of this document is written for intent classification; substitute the category task throughout if that is the chosen target. Family C is unaffected.

---

## Pre-registration

Fixed **before** any model is trained. Recording it here is what makes later results interpretable rather than post-hoc.

### The metric

**Macro-F1**, not accuracy. The class distribution is severely imbalanced, and accuracy would reward a model that never predicts the rare class at all. Per-class recall reported alongside, because failure costs are asymmetric:

| True → Predicted | Consequence | Severity |
|---|---|---|
| `LIST_ADD` → `EXPENSE` | Bogus expense written | High — corrupts the ledger |
| `OTHER` → `EXPENSE` | Bogus expense from chatter | High — corrupts the ledger |
| `EXPENSE` → `LIST_ADD` | Bogus shopping item | Medium — visible, easily deleted |
| anything → `OTHER` | Nothing happens; user rephrases | Low |

Errors that write into the ledger are the ones to weight. A model with lower macro-F1 but fewer ledger-corrupting errors may be preferable — a judgement made **with the confusion matrix in hand**, and the reasoning recorded.

### The baseline

The LLM classifier's own macro-F1 on the gold set. Not a published benchmark — the thing actually in production.

### The gold set

Hand-labelled, checked into the repository, immutable once created. **Size to be determined by the power calculation in A0, not assumed.**

### The cutover rule

The local model replaces the LLM only when **all** hold:

1. Local macro-F1 is not distinguishable from the LLM's at the resolution the gold set supports (see A0 — a point threshold finer than the measurement is not a criterion).
2. No class has recall below 0.80.
3. Shadow-mode disagreement < 3% for two consecutive weeks.
4. On sampled disagreements, the local model is correct at least as often as the LLM.

### Stopping criteria

Abandon the modelling half (keep the serving experiments) if, after augmentation and embeddings have both been tried, the local model is clearly and reproducibly worse. Record why; a negative result is a complete outcome.

---

## Hypothesis Family A — Data

The riskiest hypotheses in the plan. Test first.

### A0 — The gold set is large enough to resolve the threshold

**Statement.** A gold set of size *N* yields a confidence interval on macro-F1 narrow enough that the cutover criterion is a real test rather than noise.

**Why this comes first.** An earlier draft specified "300 messages" and "within 2 points macro-F1." With four imbalanced classes, the rarest class in a 300-sample stratified draw has perhaps 15 examples, giving a confidence interval on macro-F1 plausibly wider than ±4 points. A 2-point threshold finer than the measurement is not rigour, it is theatre.

**Experiment.** Given the observed class distribution from A1, compute the confidence interval on macro-F1 for candidate gold-set sizes (bootstrap or normal approximation on per-class recall). Choose *N* such that the interval is narrower than the difference worth detecting.

**Decision rule.** If the required *N* exceeds what can plausibly be hand-labelled, **change the criterion, not the sample size**: state the cutover rule as an interval-overlap test ("the local model's CI overlaps the LLM's") rather than a point threshold. Report intervals, never bare point estimates.

### A1 — Enough data accumulates in a workable timeframe

**Statement.** The tier-2 message population produces at least 50 examples of the rarest class within six months.

**Status: already falsified by observation.** Real traffic over a fourteen-day window is roughly **0.8 messages per day**, of which a substantial fraction are keyword-matched commands that never reach tier 2. Projecting six months gives on the order of 100–150 classified messages across three imbalanced classes. `LIST_ADD` and `OTHER` will each have a few dozen examples at best.

**Consequence.** Synthetic augmentation (B4) is not a fallback, it is **the primary data strategy**, and the gold set will be substantially hand-written rather than sampled from real traffic. Plan accordingly rather than discovering it four months in.

**Still worth measuring.** After phase 3 iteration 2 ships, track observed per-class volume for four weeks and update the projection. The rate may differ once the list domain is in use.

### A2 — LLM silver labels are accurate enough to train on

**Statement.** The LLM classifier achieves macro-F1 ≥ 0.90 against the gold set.

**Why it might be false.** A model trained on another model's output inherits its errors — label noise caps achievable performance. If the LLM is at 0.85, the student is capped near 0.85 regardless of architecture.

**Experiment.** Hand-label the gold set. Compute the LLM's macro-F1 and confusion matrix against it.

**Decision rule.** Below 0.90 → do not train on raw silver labels. Improve the classifier prompt and re-measure, or introduce a human correction pass. Either way, record the LLM's per-class errors — they predict where the student will fail.

### A3 — Downstream outcomes are a valid correction signal

**Statement.** "An `expense` row exists for this `inbound_message`" predicts `gold_label == EXPENSE` with precision ≥ 0.95; similarly `shopping_item` rows for `LIST_ADD`.

**Why it might be false.** A misrouted message can still produce a row — an extractor told to find an expense in "we need milk" may invent one. The row would then confirm the routing decision was *acted on*, not that it was *correct*.

**The command terminal statuses help here.** Deterministic commands now end as `COMMAND` or `FAILED_COMMAND`, so outcome-based labelling can exclude both cleanly rather than confusing "handled a command" with "died mid-processing."

**Experiment.** On the gold set, cross-tabulate gold label against downstream row presence. Compute precision and recall of each heuristic.

**Decision rule.** ≥ 0.95 → use outcome-derived labels to expand training data well beyond hand-labelling. Below → discard and note why. Given A1, this is the most valuable hypothesis in Family A: it is the only path to label volume that doesn't depend on synthetic data.

---

## Hypothesis Family B — Modelling

### B1 — A linear model on char n-grams matches the LLM (headline)

**Statement.** TF-IDF over character n-grams plus logistic regression is not distinguishable from the LLM baseline at the resolution A0 establishes.

**Why it might be false.** The classes share vocabulary heavily — "we need milk" and "spent 500 on milk" differ by a few tokens, and the discriminating signal is small. With perhaps 50–200 examples per class, many of them synthetic, this is exactly the low-data regime where sparse linear models struggle and pretrained representations earn their keep. **This hypothesis is genuinely uncertain and an earlier draft framed it too optimistically.**

**Experiment.** `TfidfVectorizer(analyzer=…, ngram_range=(2,5))` → `LogisticRegression`. Stratified 5-fold CV for model selection; the gold set is touched **once**, at the end. Grid over n-gram range, min document frequency, regularisation.

**Measurement.** Macro-F1 with confidence interval, per-class precision/recall, confusion matrix, ledger-corrupting error count.

**Secondary observation.** Inspect highest-weight n-grams per class. Interpretable features ("perlu", "need", "bayar", "spent") support the simple-model bet; noise-like weights suggest memorisation.

### B2 — Character n-grams handle mixed-language input without tokenization

**Statement.** Char n-grams match or beat a whitespace-tokenised word-level baseline on the mixed-script subset.

**Revised risk assessment.** An earlier draft framed this around Japanese and raised `analyzer='char_wb'` as a concern: `char_wb` extracts n-grams within whitespace-delimited "words," so a Japanese sentence with no whitespace collapses into one padded token and behaves unlike English.

Real traffic is **predominantly Indonesian and English** (`bayar`, `jajan`, `pasir kucing`), both whitespace-delimited, with Japanese likely confined to merchant names. So `char_wb` behaves normally on the bulk of the data and the tokenizer question largely dissolves.

**Still worth testing**, because Japanese merchant names do appear and would be the failure surface. **Compare `char_wb` against plain `char`** on the JP-containing subset specifically; if they diverge, `char` is the safer default and also the simpler behaviour to reimplement in Kotlin (C2).

**Decision rule.** Adopt whichever wins on the mixed subset. Avoid adding a Japanese tokenizer to the serving path unless the gap is large — it is a substantial dependency for a minority of the data.

### B3 — Sentence embeddings do not justify their cost

**Statement.** Frozen multilingual sentence embeddings plus a linear head improve macro-F1 by less than 3 points over B1.

**Why it might be false.** This is the low-data, high-vocabulary-overlap regime where pretrained representations typically win decisively. **There is a real chance the honest answer is that embeddings are required** — which makes the serving story ONNX-only and adds a ~100MB artifact to a small Fly machine, materially changing C3 and C5.

**Experiment.** Embed the same folds (multilingual-E5-small or LaBSE — both handle Indonesian and Japanese), same logistic head, direct comparison.

**Decision rule.** Gain < 3 points → keep n-grams; the serving story is a weight matrix rather than a transformer. Gain ≥ 3 points → adopt embeddings.

**This is a hypothesis we hope is true**, which is a bias worth naming. Guard against it by fixing the 3-point threshold now and honouring it.

### B4 — Synthetic augmentation rescues the rare classes

**Statement.** LLM-generated paraphrases improve rare-class recall by ≥ 10 points without reducing macro-F1 on the *real-data* gold set.

**Given A1's falsification, this is a required component, not an enhancement.**

**Why it might still fail.** Generated paraphrases may cluster more narrowly than real usage, teaching a caricature of how the household phrases things — performing well on synthetic-like input and poorly on real.

**Experiment.** Generate 100 paraphrases per class across Indonesian/English/mixed, prompting for variation in politeness, abbreviation, and code-switching. Train with and without. **Evaluate exclusively on the real-data gold set**; synthetic examples never enter evaluation.

**Threat.** Synthetic data from the same LLM that produces silver labels compounds A2's circularity. Mitigate with a human review pass over a sample for naturalness — specifically, does it sound like *this household*, not merely like Indonesian.

### B5 — Class imbalance requires explicit handling

**Statement.** Class-weighted training improves macro-F1 by ≥ 2 points over unweighted.

**Experiment.** `class_weight='balanced'` versus `None`, plus minority oversampling, same folds.

**Note.** A tuning question rather than a genuine hypothesis, pre-registered so the better result isn't quietly presented as the plan.

---

## Hypothesis Family C — Serving

Most of the stated learning value. Worth executing carefully even if Family B is inconclusive — **and unaffected by which classification target is chosen.**

### C1 — sklearn and ONNX-in-JVM produce numerically equivalent predictions

**Statement.** For 1000 held-out messages, ONNX Runtime in the JVM and sklearn in Python produce identical argmax predictions, with max absolute probability difference < 1e-5.

**Why it might be false.** A real and common bug class. Sources: float32 vs float64 accumulation, differing BLAS, and — most often — **preprocessing mismatch**, since the TF-IDF vectoriser must be exported into the ONNX graph or faithfully reimplemented. Any Unicode normalisation, lowercasing, or whitespace difference silently changes features.

**Experiment.** Convert with `skl2onnx` including the vectoriser in the pipeline. Run 1000 messages through both. Assert argmax agreement and probability delta. Deliberately include mixed script, full-width digits, emoji, unusual whitespace, and the `<@Uxxxx>` mention prefix.

**Decision rule.** Any argmax disagreement is a blocker. Investigate until understood — "close enough" is unacceptable, because the failure will surface on a rare input in production, which is exactly when it matters.

**The single highest-value experiment in the phase.** The debugging is the lesson.

### C2 — Hand-rolled Kotlin inference reproduces sklearn exactly

**Statement.** A dependency-free Kotlin implementation — vocabulary and weights exported as JSON, sparse dot product computed directly — matches sklearn's argmax on the same 1000 messages.

**Why it matters.** Primarily pedagogical: it demonstrates that serving a linear classifier is a hash lookup and multiply-adds. Secondarily practical: zero dependencies, sub-millisecond, trivially unit-testable, no off-heap memory.

**Expected difficulty.** N-gram extraction and normalisation must match sklearn precisely, including analyzer-specific boundary padding. Reading the sklearn source is part of the exercise — and is a reason to prefer plain `char` over `char_wb` if B2 permits.

**Decision rule.** If C2 succeeds and B3 is not adopted, prefer this in production. Implement the ONNX path regardless — C1 is the transferable skill.

### C3 — Inference latency is negligible

**Statement.** p99 single-message inference < 5ms on the production machine, measured in-process.

**Experiment.** Benchmark inside the deployed container: 10,000 iterations, p50/p95/p99, both implementations, cold and warm.

**Decision rule.** Above 5ms is not a blocker — even 50ms beats a ~1s network call — but investigate, as it suggests a feature-extraction problem rather than a model one.

### C4 — Dispatcher choice measurably affects behaviour under load

**Statement.** Inference on `Dispatchers.IO` degrades throughput versus `Dispatchers.Default` under concurrent load.

**Why it is probably unmeasurable here.** At 0.8 messages per day, concurrency is effectively one. The effect is real in principle — `Dispatchers.IO` is sized assuming threads block on I/O and oversubscribes cores for CPU-bound work — but almost certainly invisible at this scale.

**Experiment.** Synthetic load at 1, 10, 100 concurrent classifications on both; measure throughput and latency distribution. **Report honestly if the difference is within noise.**

**Decision rule.** Use `Dispatchers.Default` (or a dedicated bounded dispatcher) regardless, on principle. The experiment's value is quantifying whether the principle matters *here* — a more honest position than asserting it does.

### C5 — Memory footprint fits the machine

**Statement.** Model load and serving increases steady-state RSS by less than 100MB.

**Why it might be false.** ONNX Runtime allocates off-heap, invisible to JVM heap monitoring. The Fly machine is small and already uses swap for the boot spike. An embedding model (B3) adds substantially more.

**Experiment.** Container RSS before/after load and under sustained inference; compare C1 and C2 implementations.

**Decision rule.** If RSS threatens the machine, that is a strong argument for C2 over C1, or for scaling — a decision made with numbers rather than assumed.

---

## Hypothesis Family D — Operations

### D1 — Shadow-mode disagreement converges

**Statement.** With both classifiers running and only the LLM's answer used, disagreement falls below 3% and stays there for two consecutive weeks.

**Experiment.** Log both predictions and confidences. Track weekly disagreement. Sample disagreements and hand-adjudicate which was correct.

**Why adjudication matters.** A falling rate alone is ambiguous — it could mean the model improved, or that message diversity fell. Adjudication distinguishes them and feeds cutover criterion 4.

**Volume caveat.** At observed traffic, "20 disagreements per week" may be unreachable; a 3% rate on ~5 tier-2 messages per week is statistically meaningless. **Shadow mode may need to run for months, or be evaluated on a replayed corpus rather than live traffic.** Decide which before starting.

### D2 — Concept drift is slow

**Statement.** A model trained on the earliest 70% and evaluated on the most recent 30% performs within 3 points of the same model under random 70/30 splits.

**Why it might be false.** Vocabulary shifts as categories are renamed and phrasing settles into shorthand. Random splits leak future information and overstate performance.

**Decision rule.** Gap ≥ 3 points → drift is real; establish a retraining cadence and treat all random-split numbers as optimistic.

### D3 — The system degrades gracefully

**Statement.** With the model missing, corrupt, or throwing, routing falls back to the LLM and no message is dropped.

**Experiment.** Automated chaos cases: delete the resource, truncate it, feed pathological input (empty, 10,000 characters, pure emoji, invalid UTF-8).

**Decision rule.** Non-negotiable. Must pass before shadow mode begins.

---

## Threats to Validity

**Circularity.** The LLM produces silver labels, the baseline, and the synthetic data. The gold set is the only independent anchor, which makes its labelling quality load-bearing.

**Labeller bias.** Labelled by the person who wrote the classifier prompt, so labels drift toward what the prompt *says* rather than what the household *means*. Mitigate by labelling from the raw message with the prompt out of sight.

**Two-user idiolect.** The model serves two people and will overfit to their phrasing. **Desirable in deployment, disqualifying for any general claim.** No conclusion here transfers to a general-purpose classifier, and the write-up should not imply otherwise.

**Severe data scarcity.** At ~0.8 messages/day this is a genuinely small-data problem. Confidence intervals will be wide; per-class metrics for rare classes rest on a handful of examples. Report intervals, not point estimates — this is what A0 exists to make explicit.

**Multiple comparisons.** Many configurations across B1–B5. Selecting the best on CV and reporting its gold score risks optimistic bias. The gold set is touched **once per hypothesis family**.

---

## Sequence

1. **Decide the target** — intent routing (contingent on phase 3 iteration 2) or category classification. This determines everything downstream.
2. **Instrument.** For intent: `intent` + `intent_confidence` on `inbound_message`, logged from the day phase 3 iteration 2 ships. For category: `categorization_event` already captures what's needed. *Every week without instrumentation is a week of unrecoverable data.*
3. **Collect** four weeks minimum; measure A1 and run the A0 power calculation.
4. **Gold-label** at the size A0 dictates; test A2 and A3.
5. **Model:** B1 → B2 → B5, then B3 and B4 as results dictate. Expect B4 to be required.
6. **Serve:** C2 (hand-rolled first — teaches most, no dependencies), then C1 (ONNX, the transferable pattern), then C3–C5.
7. **Shadow** — duration set by the volume caveat in D1, with D3 passing beforehand.
8. **Cut over** only if all four criteria are met. Keep the LLM path permanently as fallback.

---

## Artifacts

Produced regardless of whether cutover happens:

- `eval/gold_set.jsonl` — hand-labelled, in the repository, immutable once created
- `training/` — extraction, feature pipeline, training script, all seeded for reproducibility
- Model artifacts — ONNX graph and/or Kotlin-readable JSON weights, shipped **inside the jar** alongside migrations, so deployed code and model can never mismatch
- `bench/` — parity harness (C1/C2) and latency harness (C3)
- **A written findings document** — hypotheses, results including falsified ones, and the reasoning behind the cutover decision

That last is the actual deliverable of a research phase. A negative result, clearly argued, is a complete outcome.

---

## Non-Goals

- Beating a published benchmark. The only baseline that matters is what is in production.
- Building a general-purpose classifier. This one serves two people and should overfit to them.
- Removing the LLM. It remains the fallback and the extractor; only one classification decision is in scope.
- Online or continuous learning. Retraining is deliberate, manual, and evaluated.
