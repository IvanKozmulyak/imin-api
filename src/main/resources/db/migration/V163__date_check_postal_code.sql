-- V163: the venue postcode of a date check, so French school zones resolve beyond the city lookup.
ALTER TABLE date_check ADD COLUMN postal_code VARCHAR(16);
