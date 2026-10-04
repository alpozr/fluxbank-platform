ALTER TABLE users
    DROP CONSTRAINT users_email_key;

CREATE UNIQUE INDEX users_email_lower_unique_idx
    ON users (LOWER(email));