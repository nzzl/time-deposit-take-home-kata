-- Schema for the XA Bank time deposit service.
--
-- Table and column names are the brief's, verbatim, in camelCase. Postgres folds unquoted
-- identifiers to lower case, so every identifier is double-quoted to preserve the exact names the
-- brief specifies (SPEC.md A15, DECISIONS.md D14). Every reference in the adapter's SQL quotes them
-- for the same reason.
--
-- "balance" and "amount" are unconstrained NUMERIC. A scaled column such as NUMERIC(19,2) would
-- silently round the pinned characterization value 6.029999999999999 to 6.03 and break behaviour
-- preservation at the persistence boundary (SPEC.md A7, DECISIONS.md D6). Unconstrained NUMERIC
-- keeps every significant digit, which is what makes the Double<->Decimal round-trip lossless.

CREATE TABLE IF NOT EXISTS "timeDeposits" (
    "id"       INTEGER      PRIMARY KEY,
    "planType" VARCHAR(255) NOT NULL,
    "days"     INTEGER      NOT NULL,
    "balance"  NUMERIC      NOT NULL
);

CREATE TABLE IF NOT EXISTS "withdrawals" (
    "id"            INTEGER PRIMARY KEY,
    "timeDepositId" INTEGER NOT NULL REFERENCES "timeDeposits" ("id"),
    "amount"        NUMERIC NOT NULL,
    "date"          DATE    NOT NULL
);
