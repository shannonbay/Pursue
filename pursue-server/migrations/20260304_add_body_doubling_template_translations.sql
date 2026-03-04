SET client_encoding = 'UTF8';

BEGIN;

-- ─── GERMAN (de) ──────────────────────────────────────────────────────────────

INSERT INTO group_template_translations (template_id, language, title, description)
SELECT t.id, 'de', v.de_title, v.de_description
FROM (VALUES
  ('Body Doubling', 'Body Doubling', 'Arbeite neben anderen, um Dinge zu erledigen. Plane Fokus-Sessions, logge deine Einheiten und unterstützt euch gegenseitig — egal ob beim Lernen, Schreiben oder an einem Projekt.'),
  ('Deep Work Club', 'Deep-Work-Club', 'Schütze deine besten Stunden. Logge deine Deep-Work-Sessions, verfolge deine Zeit und teile, woran du arbeitest — mit einer Gruppe, die echten Fokus über Scheinproduktivität stellt.')
) AS v(en_title, de_title, de_description)
JOIN group_templates t ON t.title = v.en_title
ON CONFLICT (template_id, language) DO NOTHING;

INSERT INTO group_template_goal_translations (goal_id, language, title, description, log_title_prompt)
SELECT tg.id, 'de', v.de_title, v.de_description, v.de_log_prompt
FROM (VALUES
  ('Body Doubling', 'Focus session done',     'Fokus-Session abgeschlossen',        NULL, NULL),
  ('Body Doubling', 'Focus time',             'Fokuszeit',                           NULL, NULL),
  ('Deep Work Club', 'Deep work session done', 'Deep-Work-Session abgeschlossen',    NULL, NULL),
  ('Deep Work Club', 'What did you work on?',  'Woran hast du gearbeitet?',          NULL, NULL),
  ('Deep Work Club', 'Deep work time',         'Deep-Work-Zeit',                     NULL, NULL)
) AS v(en_template, en_goal, de_title, de_description, de_log_prompt)
JOIN group_templates t ON t.title = v.en_template
JOIN group_template_goals tg ON tg.template_id = t.id AND tg.title = v.en_goal
ON CONFLICT (goal_id, language) DO NOTHING;

-- ─── SPANISH (es) ─────────────────────────────────────────────────────────────

INSERT INTO group_template_translations (template_id, language, title, description)
SELECT t.id, 'es', v.es_title, v.es_description
FROM (VALUES
  ('Body Doubling', 'Body Doubling', 'Trabaja junto a otros para hacer las cosas. Programa sesiones de enfoque, registra tus sesiones y apóyense mutuamente, ya sea estudiando, escribiendo o en un proyecto.'),
  ('Deep Work Club', 'Club de Trabajo Profundo', 'Protege tus mejores horas. Registra tus sesiones de trabajo profundo, controla tu tiempo y comparte en qué estás trabajando con un grupo que valora el foco real sobre la apariencia de productividad.')
) AS v(en_title, es_title, es_description)
JOIN group_templates t ON t.title = v.en_title
ON CONFLICT (template_id, language) DO NOTHING;

INSERT INTO group_template_goal_translations (goal_id, language, title, description, log_title_prompt)
SELECT tg.id, 'es', v.es_title, v.es_description, v.es_log_prompt
FROM (VALUES
  ('Body Doubling', 'Focus session done',     'Sesión de enfoque completada',              NULL, NULL),
  ('Body Doubling', 'Focus time',             'Tiempo de enfoque',                          NULL, NULL),
  ('Deep Work Club', 'Deep work session done', 'Sesión de trabajo profundo completada',     NULL, NULL),
  ('Deep Work Club', 'What did you work on?',  '¿En qué trabajaste?',                       NULL, NULL),
  ('Deep Work Club', 'Deep work time',         'Tiempo de trabajo profundo',                NULL, NULL)
) AS v(en_template, en_goal, es_title, es_description, es_log_prompt)
JOIN group_templates t ON t.title = v.en_template
JOIN group_template_goals tg ON tg.template_id = t.id AND tg.title = v.en_goal
ON CONFLICT (goal_id, language) DO NOTHING;

-- ─── FRENCH (fr) ──────────────────────────────────────────────────────────────

INSERT INTO group_template_translations (template_id, language, title, description)
SELECT t.id, 'fr', v.fr_title, v.fr_description
FROM (VALUES
  ('Body Doubling', 'Body Doubling', 'Travaille aux côtés d''autres pour avancer. Planifie des sessions de concentration, enregistre tes séances et soutenez-vous mutuellement — que ce soit pour étudier, écrire ou travailler sur un projet.'),
  ('Deep Work Club', 'Club de Travail Profond', 'Protège tes meilleures heures. Enregistre tes sessions de travail profond, suis ton temps et partage ce sur quoi tu travailles avec un groupe qui valorise la vraie concentration plutôt que l''agitation.')
) AS v(en_title, fr_title, fr_description)
JOIN group_templates t ON t.title = v.en_title
ON CONFLICT (template_id, language) DO NOTHING;

INSERT INTO group_template_goal_translations (goal_id, language, title, description, log_title_prompt)
SELECT tg.id, 'fr', v.fr_title, v.fr_description, v.fr_log_prompt
FROM (VALUES
  ('Body Doubling', 'Focus session done',     'Session de concentration terminée',          NULL, NULL),
  ('Body Doubling', 'Focus time',             'Temps de concentration',                      NULL, NULL),
  ('Deep Work Club', 'Deep work session done', 'Session de travail profond terminée',        NULL, NULL),
  ('Deep Work Club', 'What did you work on?',  'Sur quoi as-tu travaillé ?',                 NULL, NULL),
  ('Deep Work Club', 'Deep work time',         'Temps de travail profond',                   NULL, NULL)
) AS v(en_template, en_goal, fr_title, fr_description, fr_log_prompt)
JOIN group_templates t ON t.title = v.en_template
JOIN group_template_goals tg ON tg.template_id = t.id AND tg.title = v.en_goal
ON CONFLICT (goal_id, language) DO NOTHING;

-- ─── PORTUGUESE BRAZIL (pt-BR) ────────────────────────────────────────────────

INSERT INTO group_template_translations (template_id, language, title, description)
SELECT t.id, 'pt-BR', v.pt_title, v.pt_description
FROM (VALUES
  ('Body Doubling', 'Body Doubling', 'Trabalhe ao lado de outros para realizar coisas. Agende sessões de foco, registre suas sessões e apoiem-se mutuamente — seja estudando, escrevendo ou trabalhando em um projeto.'),
  ('Deep Work Club', 'Clube de Trabalho Profundo', 'Proteja suas melhores horas. Registre suas sessões de trabalho profundo, acompanhe seu tempo e compartilhe o que está construindo com um grupo que valoriza o foco real em vez da falsa produtividade.')
) AS v(en_title, pt_title, pt_description)
JOIN group_templates t ON t.title = v.en_title
ON CONFLICT (template_id, language) DO NOTHING;

INSERT INTO group_template_goal_translations (goal_id, language, title, description, log_title_prompt)
SELECT tg.id, 'pt-BR', v.pt_title, v.pt_description, v.pt_log_prompt
FROM (VALUES
  ('Body Doubling', 'Focus session done',     'Sessão de foco concluída',                   NULL, NULL),
  ('Body Doubling', 'Focus time',             'Tempo de foco',                               NULL, NULL),
  ('Deep Work Club', 'Deep work session done', 'Sessão de trabalho profundo concluída',      NULL, NULL),
  ('Deep Work Club', 'What did you work on?',  'Em que você trabalhou?',                     NULL, NULL),
  ('Deep Work Club', 'Deep work time',         'Tempo de trabalho profundo',                 NULL, NULL)
) AS v(en_template, en_goal, pt_title, pt_description, pt_log_prompt)
JOIN group_templates t ON t.title = v.en_template
JOIN group_template_goals tg ON tg.template_id = t.id AND tg.title = v.en_goal
ON CONFLICT (goal_id, language) DO NOTHING;

-- ─── CHINESE (zh) ─────────────────────────────────────────────────────────────

INSERT INTO group_template_translations (template_id, language, title, description)
SELECT t.id, 'zh', v.zh_title, v.zh_description
FROM (VALUES
  ('Body Doubling', '共同专注', '与他人并肩工作，完成任务。安排专注会议，记录你的会议，互相支持——无论是学习、写作还是深入项目。'),
  ('Deep Work Club', '深度工作俱乐部', '保护你最好的时光。记录深度工作会议，追踪时间，与一个重视真正专注而非虚假忙碌的团队分享你正在构建的内容。')
) AS v(en_title, zh_title, zh_description)
JOIN group_templates t ON t.title = v.en_title
ON CONFLICT (template_id, language) DO NOTHING;

INSERT INTO group_template_goal_translations (goal_id, language, title, description, log_title_prompt)
SELECT tg.id, 'zh', v.zh_title, v.zh_description, v.zh_log_prompt
FROM (VALUES
  ('Body Doubling', 'Focus session done',     '专注会议完成',    NULL, NULL),
  ('Body Doubling', 'Focus time',             '专注时间',        NULL, NULL),
  ('Deep Work Club', 'Deep work session done', '深度工作会议完成', NULL, NULL),
  ('Deep Work Club', 'What did you work on?',  '你做了什么工作？', NULL, NULL),
  ('Deep Work Club', 'Deep work time',         '深度工作时间',    NULL, NULL)
) AS v(en_template, en_goal, zh_title, zh_description, zh_log_prompt)
JOIN group_templates t ON t.title = v.en_template
JOIN group_template_goals tg ON tg.template_id = t.id AND tg.title = v.en_goal
ON CONFLICT (goal_id, language) DO NOTHING;

COMMIT;
