-- PO ruling P15 (2026-11-01, "Add a model check, refuse-only"), widened by P16(c) (2026-11-03, "Check every answer"):
-- after every code check has passed, every চ্যাট answer the model wrote is read by a second, small model call that can
-- only refuse it (ChatAnswerCheck). When this header was last edited it was not on origin/main and not applied in
-- production or the shared dev database (checked 2026-11-04; CI's and Testcontainers' fresh databases apply it each run).
--
-- 1. The check is metered like every model call: one ai_call_log row each, under its own purpose 'chat_check', so what the
--    check costs per answer has an answer in the data and never blends into 'chat'.
-- 2. chat_turn.check_calls counts the check's calls of a turn (at most one); model_calls keeps meaning the chat model's own
--    (an answer and its one repair). A check call is only ever made on a turn a model answered.
-- 3. An answer the check refused, or could not read (an error, a timeout, an unreadable verdict, no check client), is shown
--    as the fixed reply under handler 'model_check': a FALLBACK on a counted, modelled turn, never an answer.
--
-- Version 32: #166 (CHATWEB) shipped first and took V31__chat_turn_medicines.sql; this file was V31 on its branch and was
-- renamed on the restack onto #166; the V31 name was never on origin/main, so never deployed. Nothing in code names it
-- by number (review of #173, finding 20).
ALTER TABLE ${flyway:defaultSchema}.ai_call_log DROP CONSTRAINT ai_call_log_purpose_check;
ALTER TABLE ${flyway:defaultSchema}.ai_call_log ADD CONSTRAINT ai_call_log_purpose_check
    CHECK (purpose IN ('ocr', 'extract', 'explain', 'chat', 'nudge', 'summary', 'crop_verify', 'chat_check'));

ALTER TABLE ${flyway:defaultSchema}.chat_turn
    ADD COLUMN check_calls integer NOT NULL DEFAULT 0 CONSTRAINT chat_turn_check_calls_bound CHECK (check_calls BETWEEN 0 AND 1);

ALTER TABLE ${flyway:defaultSchema}.chat_turn DROP CONSTRAINT chat_turn_handler_check;
ALTER TABLE ${flyway:defaultSchema}.chat_turn ADD CONSTRAINT chat_turn_handler_check
    CHECK (handler IN ('red_flag', 'off', 'no_model', 'cap', 'model', 'repair', 'fixed_after_repair', 'model_error', 'medicine_purpose',
                       'model_check'));
ALTER TABLE ${flyway:defaultSchema}.chat_turn ADD CONSTRAINT chat_turn_check_after_model
    CHECK (check_calls = 0 OR model_calls >= 1);
ALTER TABLE ${flyway:defaultSchema}.chat_turn ADD CONSTRAINT chat_turn_check_refusal_is_fallback
    CHECK (handler IS DISTINCT FROM 'model_check' OR (kind IS NOT DISTINCT FROM 'fallback' AND counted AND model_calls >= 1));
-- 4. A check refusal is the fixed reply: it cites no paper and quotes no line, so it stores and returns no crop. Held here,
--    not only in the one reply the service builds, so no later writer can store a refusal that draws a crop beside text
--    that names no medicine (second review of #173, finding 2; the V13 → V14 lesson). V31's chat_turn_medicine_on_a_cited_paper
--    makes an empty document_ids imply empty medicine_document_ids; both are named so the rule reads on its own.
ALTER TABLE ${flyway:defaultSchema}.chat_turn ADD CONSTRAINT chat_turn_check_refusal_quotes_nothing
    CHECK (handler IS DISTINCT FROM 'model_check'
           OR (cardinality(document_ids) = 0 AND cardinality(medicine_document_ids) = 0 AND cardinality(medicine_crop_keys) = 0));

COMMENT ON COLUMN ${flyway:defaultSchema}.chat_turn.check_calls IS
    'P15: the refuse-only model check''s calls on this turn (0 or 1); model_calls counts the chat model''s own';
COMMENT ON COLUMN ${flyway:defaultSchema}.chat_turn.handler IS
    'how the shown answer was made: red_flag, off, no_model, cap, model, repair, fixed_after_repair, model_error, medicine_purpose (P14: code from the stored lines, no model call), or model_check (P15: the model check refused the answer, or could not read it)';
COMMENT ON COLUMN ${flyway:defaultSchema}.chat_turn.cost_usd IS
    'every priced call of the turn, the model check''s included; null when none was priced';
