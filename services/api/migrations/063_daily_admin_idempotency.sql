CREATE TABLE daily_admin_request_keys (
  admin_id uuid NOT NULL REFERENCES admins(id) ON DELETE CASCADE,
  operation text NOT NULL CHECK (operation ~ '^[a-z_]{1,64}$'),
  idempotency_key uuid NOT NULL,
  request_hash text NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
  response jsonb NOT NULL CHECK (jsonb_typeof(response)='object' AND octet_length(response::text)<=32768),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (admin_id,operation,idempotency_key)
);
