-- Update heat score and tier for your group
UPDATE group_heat
SET heat_score = 65.00,
    heat_tier = 4,  -- Blaze (tier 5 = score 61-74)
    streak_days = 3,
    peak_score = 65.00,
    peak_date = CURRENT_DATE,
    last_calculated_at = NOW()
WHERE group_id = '8a752a57-cb94-40eb-a381-97928ea90e60';

-- If no row exists yet, insert one:
INSERT INTO group_heat (group_id, heat_score, heat_tier, streak_days, peak_score, peak_date, last_calculated_at)
VALUES ('8a752a57-cb94-40eb-a381-97928ea90e60', 65.00, 5, 3, 65.00, CURRENT_DATE, NOW())
ON CONFLICT (group_id) DO UPDATE SET
    heat_score = 65.00,
    heat_tier = 5,
    streak_days = 3,
    peak_score = 65.00,
    peak_date = CURRENT_DATE,
    last_calculated_at = NOW();