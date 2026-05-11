CREATE ROLE anon nologin;
GRANT USAGE ON SCHEMA public TO anon;

CREATE TABLE public.admins (
    id SERIAL PRIMARY KEY,
    username TEXT,
    password_hash TEXT
);

INSERT INTO public.admins (username, password_hash) VALUES
('reimu', '$pbkdf2-sha256$240000$shrineledger$9k0t4oUmLbF5258OCkSCgLHCFswMNWUPeXt4NRv-5hw');

GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO anon;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO anon;

CREATE ROLE authenticator noinherit login password 'hakurei_auth_bridge_2026';
GRANT anon TO authenticator;
