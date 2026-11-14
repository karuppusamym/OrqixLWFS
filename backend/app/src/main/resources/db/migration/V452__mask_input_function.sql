-- CG-I05 (docs/build/S151): request answers of questions classified personal or sensitive are masked in SQL-built
-- exports (workspace export, run archives) exactly as in API responses (FieldClassification.MASK, six U+2022
-- bullets). Sensitive answers are additionally stored encrypted by the application; this function masks them too.
CREATE FUNCTION orqix_mask_input(input jsonb, definition jsonb) RETURNS jsonb
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT CASE
        WHEN input IS NULL OR jsonb_typeof(input) <> 'object' THEN input
        ELSE COALESCE((
            SELECT jsonb_object_agg(e.key,
                       CASE WHEN definition #>> ARRAY['inputSchema', 'properties', e.key, 'classification']
                                 IN ('personal', 'sensitive')
                                 AND e.value <> 'null'::jsonb
                            THEN to_jsonb(U&'\2022\2022\2022\2022\2022\2022'::text)
                            ELSE e.value END)
            FROM jsonb_each(input) e), '{}'::jsonb)
    END
$$;
GRANT EXECUTE ON FUNCTION orqix_mask_input(jsonb, jsonb) TO ${app_role};
