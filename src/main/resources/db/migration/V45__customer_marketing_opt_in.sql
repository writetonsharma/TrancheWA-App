-- Marketing broadcast opt-in. Default true; a STOP reply or the admin toggle sets it false.
ALTER TABLE customers ADD COLUMN marketing_opt_in BOOLEAN NOT NULL DEFAULT TRUE;
