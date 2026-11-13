-- Source: approved REQUIREMENTS V0.4 sections 4.5 (W06) and 2.5; DEV/TEST/PROD are business environments.
-- LOCAL_TEST was this run's technical label for TEST, not a fourth business environment.
-- Normalize only that equivalent label. Keep IDs, versions, materials and historical snapshots intact.
UPDATE li_p0_channel_config SET environment='TEST',active_route=IF(active_route='LOCAL_TEST','TEST',active_route) WHERE environment='LOCAL_TEST';
ALTER TABLE li_p0_channel_config ADD CONSTRAINT ck_s1_channel_business_environment CHECK(environment IN ('DEV','TEST','PROD'));
ALTER TABLE li_p0_channel_config ADD CONSTRAINT ck_s1_no_production_mock CHECK(NOT(environment='PROD' AND mode='MOCK'));
