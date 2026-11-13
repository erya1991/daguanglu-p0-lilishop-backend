-- Approved Manager separation: review, operations, finance. Only finance maintains commission rules.
UPDATE li_role_menu SET role_id='710000000000000003'
 WHERE id=710000000000001023 AND role_id='710000000000000002' AND menu_id='710000000000001011';
UPDATE li_role SET name='S1_TEST_FINANCE' WHERE id=710000000000000003 AND description='S1_TEST';
