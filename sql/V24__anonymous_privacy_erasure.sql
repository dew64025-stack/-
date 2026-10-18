-- Created by the schema-scoped durable migrator account, never by the business account.
-- Account deletion anonymises anonymous community content and removes both legacy user_id
-- mappings and HMAC-only mappings without granting the business account SELECT on the mapping.
DELIMITER $$
CREATE PROCEDURE community_privacy_erase(
    IN p_user_id BIGINT UNSIGNED, IN p_user_search CHAR(64)
)
SQL SECURITY DEFINER
MODIFIES SQL DATA
BEGIN
    UPDATE community_comments c
    JOIN pseudo_identities i ON i.comment_id = c.id
    SET c.content = '[已删除]', c.status = 'deleted'
    WHERE i.user_id = p_user_id OR i.user_id_search = p_user_search;

    UPDATE community_posts p
    JOIN pseudo_identities i ON i.post_id = p.id
    SET p.title = '[已删除]', p.content = '[已删除]', p.status = 'deleted',
        p.deleted_at = NOW(3), p.updated_at = NOW(3), p.version = p.version + 1
    WHERE i.user_id = p_user_id OR i.user_id_search = p_user_search;

    DELETE i FROM pseudo_identities i
    WHERE i.user_id = p_user_id OR i.user_id_search = p_user_search;

    SELECT ROW_COUNT() AS erased;
END$$
DELIMITER ;
