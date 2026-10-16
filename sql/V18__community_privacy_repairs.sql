-- Created by the schema-scoped durable migrator account, never by the business account.
-- Application receives EXECUTE on these two named procedures only; no mapping SELECT.
DELIMITER $$
CREATE PROCEDURE community_owner_check(
    IN p_campus BIGINT UNSIGNED, IN p_type VARCHAR(32),
    IN p_target BIGINT UNSIGNED, IN p_proof CHAR(64)
)
SQL SECURITY DEFINER
READS SQL DATA
BEGIN
    SELECT CASE
        WHEN p_type = 'community_post' THEN EXISTS(
            SELECT 1 FROM pseudo_identities i JOIN community_posts p ON p.id=i.post_id
            WHERE p.campus_id=p_campus AND p.id=p_target AND i.user_id_search=p_proof)
        WHEN p_type = 'community_comment' THEN EXISTS(
            SELECT 1 FROM pseudo_identities i JOIN community_comments c ON c.id=i.comment_id
            JOIN community_posts p ON p.id=c.post_id
            WHERE p.campus_id=p_campus AND c.id=p_target AND i.user_id_search=p_proof)
        ELSE FALSE END AS is_owner;
END$$

CREATE PROCEDURE community_owner_posts(
    IN p_campus BIGINT UNSIGNED, IN p_proof CHAR(64), IN p_status VARCHAR(32),
    IN p_before BIGINT UNSIGNED, IN p_limit INT
)
SQL SECURITY DEFINER
READS SQL DATA
BEGIN
    IF p_limit < 1 OR p_limit > 101 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid owner page limit';
    END IF;
    SELECT p.id FROM community_posts p
    WHERE p.campus_id=p_campus AND p.id<p_before
      AND (p_status IS NULL OR p.status=p_status)
      AND EXISTS(SELECT 1 FROM pseudo_identities i WHERE i.post_id=p.id AND i.user_id_search=p_proof)
    ORDER BY p.id DESC LIMIT p_limit;
END$$
DELIMITER ;
