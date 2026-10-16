-- A crop verification is its own kind of AI call, and must be costed as one.
--
-- When a value's crop cannot be located in the OCR text, the crop cut from the model's region is re-read
-- independently before it may be stored (DR-12). That re-read is one OCR call, and sometimes one small
-- vision-model call, per unlocatable value — a per-value cost on top of the per-document extraction.
--
-- Logging it as purpose='ocr' would blend it into the page OCR that happens once per page, and the question
-- "what does verification cost per document" would have no answer in the data. It gets its own purpose so
-- the recovery can be priced against the values it recovers.
alter table baymax.ai_call_log drop constraint if exists ai_call_log_purpose_check;

alter table baymax.ai_call_log add constraint ai_call_log_purpose_check
    check (purpose in ('ocr', 'extract', 'explain', 'chat', 'nudge', 'summary', 'crop_verify'));
