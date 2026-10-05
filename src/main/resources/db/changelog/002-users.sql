CREATE TABLE banking_users (
    id uuid PRIMARY KEY,
    username varchar(64) NOT NULL UNIQUE,
    display_name varchar(100) NOT NULL,
    password_hash varchar(60) NOT NULL,
    deleted boolean NOT NULL DEFAULT false,
    token_version bigint NOT NULL DEFAULT 0 CHECK (token_version >= 0)
);
