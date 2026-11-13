-- Only creates missing menu metadata. It never changes li_role_menu or existing grants.
-- Requires CREATE ROUTINE / EXECUTE / ALTER ROUTINE for the migration account.
DELIMITER $$
CREATE PROCEDURE p0_install_resident_menu_008()
BEGIN
  DECLARE root_id BIGINT;
  DECLARE group_id BIGINT;
  DECLARE matches_count INT DEFAULT 0;
  DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
  START TRANSACTION;
  SELECT COUNT(*),MIN(id) INTO matches_count,root_id FROM li_menu WHERE name='uat-root' AND delete_flag=0;
  IF matches_count<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='RESIDENT_MENU_ROOT_MISMATCH'; END IF;
  SELECT COUNT(*),MIN(id) INTO matches_count,group_id FROM li_menu WHERE name='uat-resident-group';
  IF matches_count>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='RESIDENT_MENU_GROUP_CONFLICT'; END IF;
  IF matches_count=0 THEN
    SET group_id=UUID_SHORT();
    INSERT INTO li_menu(id,create_by,create_time,delete_flag,name,title,front_route,path,permission,parent_id,level,sort_order)
      VALUES(group_id,'UAT_008',UTC_TIMESTAMP(),0,'uat-resident-group','用户管理','Main','/users','',CAST(root_id AS CHAR),1,10);
  ELSEIF NOT EXISTS(SELECT 1 FROM li_menu WHERE id=group_id AND delete_flag=0 AND parent_id=CAST(root_id AS CHAR) AND front_route='Main' AND level=1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='RESIDENT_MENU_GROUP_CONFLICT';
  END IF;
  SELECT COUNT(*) INTO matches_count FROM li_menu WHERE name='s1-residents';
  IF matches_count>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='RESIDENT_MENU_PAGE_CONFLICT'; END IF;
  IF matches_count=0 THEN
    INSERT INTO li_menu(id,create_by,create_time,delete_flag,name,title,front_route,path,permission,parent_id,level,sort_order)
      VALUES(UUID_SHORT(),'UAT_008',UTC_TIMESTAMP(),0,'s1-residents','住户账号','p0-stage1/residents','/s1/residents','s1.resident.read',CAST(group_id AS CHAR),2,10);
  ELSEIF NOT EXISTS(SELECT 1 FROM li_menu WHERE name='s1-residents' AND delete_flag=0 AND parent_id=CAST(group_id AS CHAR) AND path='/s1/residents' AND front_route='p0-stage1/residents' AND permission='s1.resident.read') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='RESIDENT_MENU_PAGE_CONFLICT';
  END IF;
  SELECT COUNT(*) INTO matches_count FROM li_menu WHERE name='action-s1-resident-manage';
  IF matches_count>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='RESIDENT_MENU_ACTION_CONFLICT'; END IF;
  IF matches_count=0 THEN
    INSERT INTO li_menu(id,create_by,create_time,delete_flag,name,title,front_route,path,permission,parent_id,level,sort_order)
      VALUES(UUID_SHORT(),'UAT_008',UTC_TIMESTAMP(),0,'action-s1-resident-manage','启用和停用会员','','','s1.resident.manage',CAST(group_id AS CHAR),2,20);
  ELSEIF NOT EXISTS(SELECT 1 FROM li_menu WHERE name='action-s1-resident-manage' AND delete_flag=0 AND parent_id=CAST(group_id AS CHAR) AND permission='s1.resident.manage') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='RESIDENT_MENU_ACTION_CONFLICT';
  END IF;
  COMMIT;
END$$
CALL p0_install_resident_menu_008()$$
DROP PROCEDURE p0_install_resident_menu_008$$
DELIMITER ;
