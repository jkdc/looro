-- Local operator accounts: the panel authenticates against credentials created
-- and stored here, never against a federated identity provider.
ALTER TABLE admins DROP CONSTRAINT admins_provider_check;
ALTER TABLE admins ADD CONSTRAINT admins_provider_check CHECK (provider IN ('google','apple','local'));
ALTER TABLE admins ADD COLUMN password_hash text;
-- Local credentials are the only web login: a local admin without a password
-- hash cannot authenticate, and federated rows keep their historical provider.
CREATE UNIQUE INDEX admins_local_subject_idx ON admins(subject) WHERE provider = 'local';
