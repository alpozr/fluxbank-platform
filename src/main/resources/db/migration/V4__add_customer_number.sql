CREATE SEQUENCE customer_number_seq
    START WITH 1000000
    INCREMENT BY 1
    MINVALUE 1000000
    MAXVALUE 9999999
    NO CYCLE;

ALTER TABLE users
    ADD COLUMN customer_number BIGINT;

UPDATE users
SET customer_number = nextval('customer_number_seq')
WHERE customer_number IS NULL;

ALTER TABLE users
    ALTER COLUMN customer_number SET NOT NULL;

ALTER TABLE users
    ADD CONSTRAINT users_customer_number_unique
        UNIQUE (customer_number);