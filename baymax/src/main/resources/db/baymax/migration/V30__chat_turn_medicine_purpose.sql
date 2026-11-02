-- PO ruling P14, "Only what the papers say": a চ্যাট question that asks only what a medicine is for, does or
-- treats is answered by code from the stored prescription lines, with no model call (MedicinePurposeReply). Its turn is
-- stored under the new handler 'medicine_purpose', and this migration holds what that handler means: a DECLINED turn,
-- never counted, never modelled. So no code path, present or future, can store a model's answer under code's handler,
-- and a turn stored under it is provably one no model wrote (CLAUDE.md: assert the rule once, where every writer meets it).
-- The handler CHECK is V29's inline column constraint, named by Postgres; it is dropped and recreated with one more value.
ALTER TABLE ${flyway:defaultSchema}.chat_turn DROP CONSTRAINT chat_turn_handler_check;
ALTER TABLE ${flyway:defaultSchema}.chat_turn ADD CONSTRAINT chat_turn_handler_check
    CHECK (handler IN ('red_flag', 'off', 'no_model', 'cap', 'model', 'repair', 'fixed_after_repair', 'model_error', 'medicine_purpose'));
ALTER TABLE ${flyway:defaultSchema}.chat_turn ADD CONSTRAINT chat_turn_purpose_by_code
    CHECK (handler IS DISTINCT FROM 'medicine_purpose' OR (kind IS NOT DISTINCT FROM 'declined' AND model_calls = 0 AND NOT counted));

COMMENT ON COLUMN ${flyway:defaultSchema}.chat_turn.handler IS
    'how the shown answer was made: red_flag, off, no_model, cap, model, repair, fixed_after_repair, model_error, or medicine_purpose (P14: code from the stored lines, no model call)';
