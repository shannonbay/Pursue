SET client_encoding = 'UTF8';

-- Migration: add Spanish (es) template i18n translations
-- Canonical master rows (English) stay in group_templates / group_template_goals.
-- The API uses COALESCE in TypeScript: translation → English fallback.
-- Idempotent: ON CONFLICT DO NOTHING throughout.

-- =============================================================
-- es template translations
-- Lookup by English title (authoritative from DB export).
-- ON CONFLICT DO NOTHING silently skips removed templates.
-- =============================================================

INSERT INTO group_template_translations (template_id, language, title, description)
SELECT t.id, 'es', v.es_title, v.es_description
FROM (VALUES
  ('30-Day Gratitude Journal',               'Diario de Gratitud de 30 Días',         'Escribe registros diarios de gratitud.'),
  ('Screen Time',                            'Tiempo de Pantalla',                     'Retoma tu atención. Registra tu tiempo sin teléfono, comparte estrategias y mantente en menos desplazamiento automático.'),
  ('7-Day Launch Sprint',                    'Sprint de 7 Días',                       'Siete días de registro diario consistente para iniciar un nuevo hábito. Alta intensidad, corto compromiso. Perfecto para quien está comenzando y quiere probar que puede aparecer todos los días.'),
  ('The 365-Day Marathon',                   'La Maratón de 365 Días',                 'Un año completo de un hábito innegociable. No es un sprint — es un cambio de estilo de vida. Para quien se cansó de reinicios de 30 días y quiere construir algo permanente.'),
  ('30 Days of Coding',                      '30 Días de Codificación',                'Escribe código todos los días.'),
  ('Morning Routines',                       'Rutinas Matutinas',                      'Gana las mañanas juntos. Sea cual sea tu rutina — diario, ejercicio, ducha fría, lectura — registra y mantente consistente.'),
  ('Wake Up at 5am',                         'Despierta a las 5am',                    'Despierta a las 5am por 21 días.'),
  ('Home Cooking',                           'Cocina Casera',                          'Cocina más, come mejor, gasta menos. Registra cuándo cocinaste en casa y comparte qué preparaste. Sin necesidad de recetas — solo el hábito.'),
  ('8 Glasses of Water',                     '8 Vasos de Agua',                        'Bebe 8 vasos al día.'),
  ('Gardening Club',                         'Club de Jardinería',                     'Sigue tu jardín juntos — riego, siembra, deshierbe y cosecha. Perfecto para quien tiene el toque verde y quiere mantenerse responsable de su jardín.'),
  ('Book a Month',                           'Un Libro al Mes',                        'Lee 30 minutos todos los días.'),
  ('Couch to 5K',                            'Del Sofá al 5K',                         'Construye el hábito de correr mostrándote todos los días.'),
  ('No Alcohol for 30 Days',                 '30 Días Sin Alcohol',                    'Evita el alcohol durante 30 días.'),
  ('Daily Discovery',                        'Descubrimiento Diario',                  'Aprende algo nuevo todos los días — un hecho, una habilidad, un concepto o una técnica. Comparte con el grupo para aprender juntos.'),
  ('Digital Detox Weekend',                  'Fin de Semana sin Pantalla',             'Dos días completamente lejos de pantallas innecesarias.'),
  ('30-Day Read to My Kids Challenge',       'Desafío de 30 Días: Leer a Mis Hijos',  'Lee a tus hijos todos los días durante 30 días. Incluso 10 minutos antes de dormir construyen un amor duradero por la lectura — y te dan una razón para desacelerar juntos.'),
  ('Whole30',                                'Whole30',                                'Come solo alimentos integrales todos los días.'),
  ('No Social Media for 30 Days',            '30 Días Sin Redes Sociales',             'Evita las redes sociales todos los días.'),
  ('Cold Shower Challenge',                  'Desafío de Ducha Fría',                  'Toma una ducha fría todos los días.'),
  ('Language Practice',                      'Práctica de Idiomas',                    'La práctica diaria logra fluidez. Manténganse en el camino, ya sea aprendiendo español, japonés o cualquier otro idioma.'),
  ('Alcohol-Free',                           'Sin Alcohol',                            'Ya sea que tengas curiosidad sobre la sobriedad, estés en recuperación o simplemente cansado de beber — este es un grupo de responsabilidad a largo plazo, no un reinicio de 30 días. Tranquilo, solidario, sin presión.'),
  ('Nature Walks',                           'Caminatas en la Naturaleza',             'Sal, respira profundo y registra. Un grupo de responsabilidad tranquilo para quien quiere hacer del tiempo en la naturaleza un hábito regular.'),
  ('Weekend Workshop',                       'Taller de Fin de Semana',                'Haz, repara o construye algo cada fin de semana. Incluso las pequeñas victorias cuentan — un parche, una capa de pintura, una bisagra apretada.'),
  ('Morning Workout',                        'Entrenamiento Matutino',                 'Ejercítate antes de las 9am durante 21 días.'),
  ('The Weekly Stretch',                     'El Desafío Semanal',                     'Coraje personal como práctica. Una vez por semana, haz algo que te incomode — una conversación difícil, una situación social nueva, un riesgo creativo — y registra. El crecimiento vive fuera de tu zona de confort.'),
  ('10K Steps for a Week',                   '10,000 Pasos por una Semana',            'Un sprint corto de 7 días de movimiento.'),
  ('Prayer Group',                           'Grupo de Oración',                       'Oren juntos consistentemente. Registra tu tiempo de silencio diario y manténganse firmes.'),
  ('Read the New Testament in 260 Days',     'Lee el Nuevo Testamento en 260 Días',    'Lee un capítulo al día.'),
  ('Track Every Dollar',                     'Registra Cada Dólar',                    'Registra todos los gastos todos los días.'),
  ('Running Club',                           'Club de Corredores',                     'Registra tus carreras, comparte tus rutas y manténganse en movimiento. Sin fecha de finalización — solo millas consistentes.'),
  ('The Weekend Warrior',                    'El Guerrero del Fin de Semana',          'Una tarea de mantenimiento del hogar por semana. Reparar una fuga, pintar una habitación, revisar el auto — lo que sea ser adulto este fin de semana. Registra y mantén el hogar juntos.'),
  ('Out There Doing It',                     'Haciendo Cosas',                         'Sin proyecto específico, sin meta específica. Solo un grupo de personas que se muestran y prueban que hicieron algo productivo cada día. Registra. Cualquier cosa cuenta.'),
  ('Writers'' Room',                         'Sala de Escritores',                     'Escribe consistentemente con otros que entienden la lucha. Registra tu conteo de palabras o simplemente que te presentaste.'),
  ('Staying Connected',                      'Manteniéndote Conectado',                'Las relaciones necesitan mantenimiento. Un grupo para quien quiere hacer del contacto regular con amigos y familia un hábito innegociable — no solo un desafío de 30 días.'),
  ('10K Steps Daily',                        '10,000 Pasos Diarios',                   'Alcanza 10,000 pasos todos los días durante 30 días.'),
  ('Inbox Zero for a Month',                 'Bandeja de Entrada Cero por un Mes',     'Deja tu bandeja de entrada en cero todos los días.'),
  ('Daily Steps',                            'Pasos Diarios',                          '10,000 pasos es el desafío. Este es el estilo de vida. Un grupo permanente para quien registra sus pasos todos los días y quiere que otros hagan lo mismo.'),
  ('Book Club',                              'Club de Lectura',                        'Lee juntos, comparte pensamientos y manténganse al ritmo. Funciona para cualquier género o velocidad de lectura.'),
  ('100 Pushups a Day',                      '100 Flexiones al Día',                   'Desarrolla tu consistencia con 100 flexiones diarias.'),
  ('30 Days of Kindness',                    '30 Días de Bondad',                      'Realiza un acto de bondad al día.'),
  ('Skill Building',                         'Desarrollo de Habilidades',              'Elige una habilidad, practica consistentemente y registra tus sesiones. Ya sea guitarra, carpintería, ajedrez o acuarela — la práctica deliberada necesita responsabilidad.'),
  ('30-Day No Sugar Challenge',              'Desafío de 30 Días Sin Azúcar',          'Evita el azúcar agregada todos los días durante 30 días.'),
  ('Bird Watching',                          'Observación de Pájaros',                 'Registra tus avistamientos, sigue tu lista de vida y comparte la alegría de los pájaros con quien la entiende.'),
  ('Gratitude Journal',                      'Diario de Gratitud',                     'Tres cosas, todos los días. Una práctica simple que se acumula con el tiempo. Responsabilidad continua para quien pasó del desafío al hábito.'),
  ('Creative Practice',                      'Práctica Creativa',                      'Crea algo — cualquier cosa — consistentemente. Pintura, fotografía, música, cerámica, diseño. El medio no importa; mostrarse sí.'),
  ('DIY & Home Projects',                    'Proyectos Caseros DIY',                  'Mantén el ritmo en los proyectos del hogar. Registra tu progreso, comparte en qué trabajas y ten la responsabilidad para realmente terminar.'),
  ('No Spend Challenge',                     'Desafío Sin Gastos',                     'Sin compras innecesarias cada día.'),
  ('Daily Coding',                           'Codificación Diaria',                    '30 Días de Código te coloca en el hábito. Aquí es donde lo mantienes. Un grupo para desarrolladores, estudiantes y creadores que quieren escribir código todos los días — indefinidamente.'),
  ('Save $5 a Day',                          'Ahorra $5 al Día',                       'Ahorra $5 todos los días.'),
  ('The Polymath Project',                   'El Proyecto Polímata',                   'Un grupo para los genuinamente curiosos. Cada día, aprende algo nuevo — cualquier cosa — y registra un breve resumen de qué fue y por qué importa. El objetivo es amplitud, no profundidad.'),
  ('Sleep Accountability',                   'Responsabilidad del Sueño',              'El sueño es la base de todo. Registra tus horas, mantén horarios de sueño consistentes y siente la diferencia.'),
  ('50 Books in a Year',                     '50 Libros en un Año',                    'Mantén la lectura diaria y el progreso semanal de los libros.'),
  ('Daily Hydration',                        'Hidratación Diaria',                     'Ocho vasos parece simple. Hacerlo todos los días sin responsabilidad es más difícil de lo que parece. Un grupo informal para mantenerse mutuamente tomando suficiente agua.'),
  ('Read the Bible in 400 days',             'Lee la Biblia en 400 Días',              'Lee tres capítulos al día.'),
  ('Healthy Eating',                         'Alimentación Saludable',                 'No es una dieta — es un hábito a largo plazo. Registra cuándo comiste bien, comparte qué funciona y manténganse en el camino sin la presión de todo o nada.'),
  ('7 Hours of Sleep',                       '7 Horas de Sueño',                       'Duerme al menos 7 horas cada noche.'),
  ('Plank Challenge',                        'Desafío de Plancha',                     'Planchas diarias con progresión semanal.'),
  ('Family Fitness',                         'Fitness Familiar',                       'Pon a toda la familia en movimiento. Registra pasos, entrenamientos o cualquier cosa activa que hagan juntos.'),
  ('Strength Training',                      'Entrenamiento de Fuerza',                'Ponte fuerte juntos. Registra tus sesiones, sigue tus levantamientos y mantente consistente.'),
  ('Gym Buddies',                            'Compañeros de Gym',                      'Preséntate, registra y manténganse responsables. El grupo que entrena junto, se queda junto.'),
  ('Phone Call a Day',                       'Una Llamada al Día',                     'Haz una llamada al día.'),
  ('Language Learning Sprint',               'Sprint de Aprendizaje de Idioma',        'Estudia un idioma 15 minutos diarios.'),
  ('Cook at Home',                           'Cocina en Casa',                         'Cocina en casa todos los días.'),
  ('Savings Group',                          'Grupo de Ahorros',                       'Ahorra consistentemente y manténganse responsables. Registra tus contribuciones y observa cómo se fija el hábito.'),
  ('Daily Journaling',                       'Diario Diario',                          'Escribe en tu diario todos los días.'),
  ('Daily Scripture',                        'Escritura Diaria',                       'Biblia, Corán, Torá o cualquier texto sagrado — un hogar permanente para quien quiere leer las escrituras consistentemente y mantenerse mutuamente responsable de la práctica.'),
  ('Reach Out Daily',                        'Contacto Diario',                        'Contacta a una persona al día.'),
  ('Budget Accountability',                  'Responsabilidad del Presupuesto',        'Registra cada centavo juntos. Anota cuándo revisaste tu presupuesto o seguiste tus gastos del día. La conciencia es el primer paso.')
) AS v(en_title, es_title, es_description)
JOIN group_templates t ON t.title = v.en_title
ON CONFLICT (template_id, language) DO NOTHING;

-- =============================================================
-- es goal translations
-- Lookup by (template English title, goal English title).
-- NULL description/log_title_prompt falls back to English master.
-- ON CONFLICT DO NOTHING silently skips removed templates/goals.
-- =============================================================

INSERT INTO group_template_goal_translations (goal_id, language, title, description, log_title_prompt)
SELECT g.id, 'es', v.es_title, v.es_description, v.es_log_prompt
FROM (VALUES
  -- 30-Day Gratitude Journal
  ('30-Day Gratitude Journal',           'Gratitude entry',                          'Registro de gratitud',                        'Escribe tu lista de gratitud hoy.',                                      NULL::text),
  -- Screen Time
  ('Screen Time',                        'Stayed within screen time limit',          'Se mantuvo dentro del límite de tiempo de pantalla', NULL,             NULL),
  -- 7-Day Launch Sprint
  ('7-Day Launch Sprint',                'Showed up today',                          'Te presentaste hoy',                          NULL,                NULL),
  -- The 365-Day Marathon
  ('The 365-Day Marathon',               'Did the thing today',                      'Hiciste lo que había que hacer hoy',          NULL,                NULL),
  -- 30 Days of Coding
  ('30 Days of Coding',                  'Write code today',                         'Escribe código hoy',                          'Completa al menos una sesión de código hoy.',   NULL),
  -- Morning Routines
  ('Morning Routines',                   'Morning routine done',                     'Rutina matutina completada',                  NULL,                NULL),
  -- Wake Up at 5am
  ('Wake Up at 5am',                     'Wake by 5am',                              'Despierta a las 5am',                         'Despierta a las 5am.',                           NULL),
  -- Home Cooking (ongoing group)
  ('Home Cooking',                       'Cooked at home',                           'Cocinaste en casa',                           NULL,                NULL),
  -- 8 Glasses of Water
  ('8 Glasses of Water',                 '8 glasses of water',                       '8 vasos de agua',                             'Bebe 8 vasos de agua hoy.',                     NULL),
  -- Gardening Club (2 goals)
  ('Gardening Club',                     'Water the garden',                         'Riega el jardín',                             NULL,                NULL),
  ('Gardening Club',                     'Weekend garden session',                   'Sesión de jardinería del fin de semana',      NULL,                NULL),
  -- Book a Month
  ('Book a Month',                       'Read 30 minutes',                          'Lee 30 minutos',                              'Lee por al menos 30 minutos.',                  NULL),
  -- Couch to 5K
  ('Couch to 5K',                        'Run today',                                'Corre hoy',                                   'Completa tu sesión de carrera del día.',        NULL),
  -- No Alcohol for 30 Days
  ('No Alcohol for 30 Days',             'No alcohol today',                         'Sin alcohol hoy',                              'Evita el alcohol durante el día.',              NULL),
  -- Daily Discovery
  ('Daily Discovery',                    'Daily Discovery',                          'Descubrimiento Diario',                       'Comparte lo que aprendiste hoy',                NULL),
  -- Digital Detox Weekend
  ('Digital Detox Weekend',              'Stay off screens',                         'Manténgase lejos de pantallas',                'Manténgase lejos de pantallas no esenciales hoy.', NULL),
  -- 30-Day Read to My Kids Challenge
  ('30-Day Read to My Kids Challenge',   'Read to my kids',                          'Lee a mis hijos',                              'Lee en voz alta a tus hijos por al menos 10 minutos', NULL),
  -- Whole30
  ('Whole30',                            'Whole foods only',                         'Solo alimentos integrales',                   'Come solo alimentos integrales hoy.',           NULL),
  -- No Social Media for 30 Days
  ('No Social Media for 30 Days',        'No social media today',                    'Sin redes sociales hoy',                      'Evita las redes sociales durante el día.',      NULL),
  -- Cold Shower Challenge
  ('Cold Shower Challenge',              'Cold shower',                              'Ducha fría',                                  'Toma una ducha fría hoy.',                      NULL),
  -- Language Practice (2 goals)
  ('Language Practice',                  'Study session',                            'Sesión de estudio',                           NULL,                NULL),
  ('Language Practice',                  'Practice today',                           'Practica hoy',                                NULL,                NULL),
  -- Alcohol-Free
  ('Alcohol-Free',                       'Alcohol-free today',                       'Sin alcohol hoy',                             NULL,                NULL),
  -- Nature Walks (2 goals)
  ('Nature Walks',                       'Walk outside today',                       'Camina al aire libre hoy',                    NULL,                NULL),
  ('Nature Walks',                       'Time outdoors',                            'Tiempo al aire libre',                        NULL,                NULL),
  -- Weekend Workshop
  ('Weekend Workshop',                   'Weekend Project',                          'Proyecto del Fin de Semana',                  'Comparte lo que hiciste, reparaste o construiste este fin de semana', NULL),
  -- Morning Workout
  ('Morning Workout',                    'Workout before 9am',                       'Entrena antes de las 9am',                    'Completa un entrenamiento antes de las 9am.',  NULL),
  -- The Weekly Stretch
  ('The Weekly Stretch',                 'Did something uncomfortable this week',    'Hiciste algo incómodo esta semana',           NULL,                NULL),
  -- 10K Steps for a Week
  ('10K Steps for a Week',               '10,000 steps',                             '10,000 pasos',                                'Alcanza 10,000 pasos hoy.',                     NULL),
  -- Prayer Group
  ('Prayer Group',                       'Morning prayer / quiet time',              'Oración matutina / tiempo de silencio',       NULL,                NULL),
  -- Read the New Testament in 260 Days
  ('Read the New Testament in 260 Days', 'Read 1 chapter',                           'Lee 1 capítulo',                              'Lee 1 capítulo hoy.',                           NULL),
  -- Track Every Dollar
  ('Track Every Dollar',                 'Tracked all expenses',                     'Registraste todos los gastos',                'Registra todos los gastos hoy.',                NULL),
  -- Running Club (2 goals)
  ('Running Club',                       'Run today',                                'Corre hoy',                                   NULL,                NULL),
  ('Running Club',                       'Weekly distance',                          'Distancia semanal',                           NULL,                NULL),
  -- The Weekend Warrior
  ('The Weekend Warrior',                'Completed a home task this week',          'Completaste una tarea del hogar esta semana',  NULL,                NULL),
  -- Out There Doing It
  ('Out There Doing It',                 'Got something done today',                 'Hiciste algo productivo hoy',                 NULL,                NULL),
  -- Writers' Room (2 goals)
  ('Writers'' Room',                     'Write today',                              'Escribe hoy',                                 NULL,                NULL),
  ('Writers'' Room',                     'Word count',                               'Conteo de palabras',                          NULL,                NULL),
  -- Staying Connected
  ('Staying Connected',                  'Reached out to someone today',             'Te contactaste con alguien hoy',              NULL,                NULL),
  -- 10K Steps Daily
  ('10K Steps Daily',                    '10,000 steps',                             '10,000 pasos',                                'Camina al menos 10,000 pasos hoy.',             NULL),
  -- Inbox Zero for a Month
  ('Inbox Zero for a Month',             'Inbox cleared',                            'Bandeja de entrada vaciada',                  'Llega a cero en tu bandeja de entrada hoy.',    NULL),
  -- Daily Steps (2 goals)
  ('Daily Steps',                        'Daily steps',                              'Pasos diarios',                               NULL,                NULL),
  ('Daily Steps',                        'Step goal reached',                        'Meta de pasos alcanzada',                     NULL,                NULL),
  -- Book Club (2 goals)
  ('Book Club',                          'Read today',                               'Lee hoy',                                     NULL,                NULL),
  ('Book Club',                          'Pages read',                               'Páginas leídas',                              NULL,                NULL),
  -- 100 Pushups a Day
  ('100 Pushups a Day',                  '100 pushups',                              '100 flexiones',                               'Completa 100 flexiones hoy.',                   NULL),
  -- 30 Days of Kindness
  ('30 Days of Kindness',                'One kind act',                             'Un acto de bondad',                           'Haz un acto de bondad intencional.',            NULL),
  -- Skill Building (2 goals)
  ('Skill Building',                     'Practice session',                         'Sesión de práctica',                          NULL,                NULL),
  ('Skill Building',                     'Practised today',                          'Practicaste hoy',                             NULL,                NULL),
  -- 30-Day No Sugar Challenge
  ('30-Day No Sugar Challenge',          'No added sugar today',                     'Sin azúcar agregada hoy',                     'Evita todo azúcar agregada hoy.',               NULL),
  -- Bird Watching (2 goals)
  ('Bird Watching',                      'Time outside birdwatching',                'Tiempo al aire libre observando pájaros',     NULL,                NULL),
  ('Bird Watching',                      'Spot a new species',                       'Avista una nueva especie',                    NULL,                NULL),
  -- Gratitude Journal (ongoing)
  ('Gratitude Journal',                  'Wrote gratitude today',                    'Escribiste gratitud hoy',                     NULL,                NULL),
  -- Creative Practice
  ('Creative Practice',                  'Created something today',                  'Creaste algo hoy',                            NULL,                NULL),
  -- DIY & Home Projects
  ('DIY & Home Projects',                'Worked on a project',                      'Trabajaste en un proyecto',                   NULL,                NULL),
  -- No Spend Challenge
  ('No Spend Challenge',                 'No unnecessary purchases',                 'Sin compras innecesarias',                    'Evita gastos no esenciales hoy.',               NULL),
  -- Daily Coding
  ('Daily Coding',                       'Wrote code today',                         'Escribiste código hoy',                       NULL,                NULL),
  -- Save $5 a Day
  ('Save $5 a Day',                      'Saved $5',                                 'Ahorró $5',                                   'Ahorra al menos $5 hoy.',                       NULL),
  -- The Polymath Project
  ('The Polymath Project',               'Learned something new today',              'Aprendiste algo nuevo hoy',                   NULL,                NULL),
  -- Sleep Accountability (2 goals)
  ('Sleep Accountability',               'Hours slept',                              'Horas dormidas',                              NULL,                NULL),
  ('Sleep Accountability',               'In bed by target time',                    'En cama a la hora prevista',                  NULL,                NULL),
  -- 50 Books in a Year (2 goals)
  ('50 Books in a Year',                 'Finish 1 book',                            'Termina 1 libro',                             'Completa un libro esta semana.',                NULL),
  ('50 Books in a Year',                 'Read today',                               'Lee hoy',                                     'Haz tu sesión de lectura diaria.',              NULL),
  -- Daily Hydration (2 goals)
  ('Daily Hydration',                    'Glasses of water',                         'Vasos de agua',                               NULL,                NULL),
  ('Daily Hydration',                    'Drank enough water today',                 'Bebiste suficiente agua hoy',                 NULL,                NULL),
  -- Read the Bible in 400 days
  ('Read the Bible in 400 days',         'Read 3 chapters',                          'Lee 3 capítulos',                             'Lee 3 capítulos hoy.',                          NULL),
  -- Healthy Eating
  ('Healthy Eating',                     'Ate well today',                           'Comiste bien hoy',                            NULL,                NULL),
  -- 7 Hours of Sleep
  ('7 Hours of Sleep',                   '7+ hours sleep',                           '7+ horas de sueño',                           'Duerme al menos 7 horas.',                      NULL),
  -- Plank Challenge
  ('Plank Challenge',                    'Plank hold',                               'Plancha',                                     'Haz una plancha hoy.',                          NULL),
  -- Family Fitness
  ('Family Fitness',                     'Active for 30 minutes',                    'Activo por 30 minutos',                       NULL,                NULL),
  -- Strength Training
  ('Strength Training',                  'Strength session done',                    'Sesión de fuerza completada',                 NULL,                NULL),
  -- Gym Buddies
  ('Gym Buddies',                        'Hit the gym',                              'Fuiste al gimnasio',                          NULL,                NULL),
  -- Phone Call a Day
  ('Phone Call a Day',                   'Made a phone call',                        'Hiciste una llamada',                         'Llama a al menos una persona.',                 NULL),
  -- Language Learning Sprint
  ('Language Learning Sprint',           'Study 15 minutes',                         'Estudia 15 minutos',                          'Practica tu idioma 15 minutos.',                NULL),
  -- Cook at Home (challenge)
  ('Cook at Home',                       'Cooked at home',                           'Cocinaste en casa',                           'Cocina en casa hoy.',                           NULL),
  -- Savings Group
  ('Savings Group',                      'Saved today',                              'Ahorrate hoy',                                NULL,                NULL),
  -- Daily Journaling
  ('Daily Journaling',                   'Journal entry',                            'Entrada del diario',                          'Escribe una entrada en tu diario hoy.',         NULL),
  -- Daily Scripture
  ('Daily Scripture',                    'Read scripture today',                     'Leyó la escritura hoy',                       NULL,                NULL),
  -- Reach Out Daily
  ('Reach Out Daily',                    'Reached out to someone',                   'Te contactaste con alguien',                  'Contacta a un amigo o familiar.',               NULL),
  -- Budget Accountability
  ('Budget Accountability',              'Tracked spending today',                   'Registraste tus gastos hoy',                  NULL,                NULL)
) AS v(en_template_title, en_goal_title, es_title, es_description, es_log_prompt)
JOIN group_templates t ON t.title = v.en_template_title
JOIN group_template_goals g ON g.template_id = t.id AND g.title = v.en_goal_title
ON CONFLICT (goal_id, language) DO NOTHING;
