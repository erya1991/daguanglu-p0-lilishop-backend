-- Only new dgl_p0_s45 observation table, before the updated worker is opened.
-- A pre-migration overwritten detail is known only at its recorded last update, not at exception creation.
UPDATE li_p0_s45_exception_observation
 SET first_observed_at=last_observed_at
 WHERE observations=1 AND first_observed_at<last_observed_at;
