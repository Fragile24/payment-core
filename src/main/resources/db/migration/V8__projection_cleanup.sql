-- The projection mirrors the source values (status, amount, currency, version)
-- and the delivery that produced each row lives in processed_events. The
-- last_event_id column was written on every event and reset to NULL by repair,
-- and nothing ever read it.

ALTER TABLE payment_projection DROP COLUMN last_event_id;
