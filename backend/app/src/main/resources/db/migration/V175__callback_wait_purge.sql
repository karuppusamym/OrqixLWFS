-- Closed callback waits (resumed, answered elsewhere, timed out or request ended) are removed once
-- orqix.callbacks.retention has passed (docs/build/S104). Their tokens then answer 404 like any unknown token.
-- The audit trail keeps every issue, resume, expiry and the purge count.
GRANT DELETE ON callback_wait TO ${app_role};
