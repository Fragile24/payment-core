-- The idempotency record stores the key, the request fingerprint and the
-- response snapshot. The HTTP status of the original creation is not part of it:
-- a replay is answered with 200 by the API contract, not with the stored 201.

ALTER TABLE idempotency_keys DROP COLUMN response_status;
