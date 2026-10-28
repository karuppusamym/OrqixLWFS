-- Connection managers can delete a Kafka trigger or a webhook subscription once it is switched off (docs/build/S103).
-- The application removes its status, record and delivery rows first; the audit trail keeps the deletion.
GRANT DELETE ON webhook_subscription TO ${app_role};
GRANT DELETE ON kafka_trigger TO ${app_role};
