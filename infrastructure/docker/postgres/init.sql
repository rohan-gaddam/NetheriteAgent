-- PostgreSQL Initialization Script
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Log initialization
DO $$
BEGIN
   RAISE NOTICE 'NetheriteAgent PostgreSQL 16 database initialized successfully.';
END
$$;
