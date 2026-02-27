SET client_encoding = 'UTF8';

-- Migration: add template i18n translation tables + pt-BR seed data
-- Canonical master rows (English) stay in group_templates / group_template_goals.
-- The API uses COALESCE in TypeScript: translation → English fallback.
-- Idempotent: IF NOT EXISTS / ON CONFLICT DO NOTHING throughout.

CREATE TABLE IF NOT EXISTS group_template_translations (
  template_id UUID NOT NULL REFERENCES group_templates(id) ON DELETE CASCADE,
  language    VARCHAR(10) NOT NULL,
  title       VARCHAR(200) NOT NULL,
  description TEXT NOT NULL,
  PRIMARY KEY (template_id, language)
);

CREATE INDEX IF NOT EXISTS idx_group_template_translations
  ON group_template_translations(template_id, language);

CREATE TABLE IF NOT EXISTS group_template_goal_translations (
  goal_id           UUID NOT NULL REFERENCES group_template_goals(id) ON DELETE CASCADE,
  language          VARCHAR(10) NOT NULL,
  title             VARCHAR(200) NOT NULL,
  description       TEXT,
  log_title_prompt  VARCHAR(80),
  PRIMARY KEY (goal_id, language)
);

CREATE INDEX IF NOT EXISTS idx_group_template_goal_translations
  ON group_template_goal_translations(goal_id, language);

-- =============================================================
-- pt-BR template translations
-- Lookup by English title (authoritative from DB export).
-- ON CONFLICT DO NOTHING silently skips removed templates.
-- =============================================================

INSERT INTO group_template_translations (template_id, language, title, description)
SELECT t.id, 'pt-BR', v.pt_title, v.pt_description
FROM (VALUES
  ('30-Day Gratitude Journal',               'Diário de Gratidão de 30 Dias',         'Escreva registros diários de gratidão.'),
  ('Screen Time',                            'Tempo de Tela',                          'Retome sua atenção. Registre seu tempo sem celular, compartilhe estratégias e se comprometa com menos rolagem automática.'),
  ('7-Day Launch Sprint',                    'Sprint de 7 Dias',                       'Sete dias de registro diário consistente para iniciar um novo hábito. Alta intensidade, curto compromisso. Perfeito para quem está começando e quer provar que consegue aparecer todos os dias.'),
  ('The 365-Day Marathon',                   'A Maratona de 365 Dias',                 'Um ano inteiro com um hábito inegociável. Não é um sprint — é uma mudança de estilo de vida. Para quem cansou dos resets de 30 dias e quer construir algo permanente.'),
  ('30 Days of Coding',                      '30 Dias de Código',                      'Escreva código todos os dias.'),
  ('Morning Routines',                       'Rotinas Matinais',                       'Conquiste a manhã juntos. Seja qual for sua rotina — diário, exercício, banho frio, leitura — registre e mantenha a consistência.'),
  ('Wake Up at 5am',                         'Acordar às 5h',                          'Acorde às 5h por 21 dias.'),
  ('Home Cooking',                           'Cozinha Caseira',                        'Cozinhe mais, coma melhor, gaste menos. Registre quando cozinhou em casa e compartilhe o que fez. Sem necessidade de receitas — apenas o hábito.'),
  ('8 Glasses of Water',                     '8 Copos de Água',                        'Beba 8 copos por dia.'),
  ('Gardening Club',                         'Clube de Jardinagem',                    'Acompanhe seu jardim juntos — rega, plantio, capina e colheita. Perfeito para quem tem o polegar verde e quer se manter responsável pelo seu canteiro.'),
  ('Book a Month',                           'Um Livro por Mês',                       'Leia por 30 minutos todos os dias.'),
  ('Couch to 5K',                            'Do Sofá ao 5K',                          'Construa o hábito de correr aparecendo todos os dias.'),
  ('No Alcohol for 30 Days',                 '30 Dias Sem Álcool',                     'Fique sem álcool por 30 dias.'),
  ('Daily Discovery',                        'Descoberta Diária',                      'Aprenda algo novo todos os dias — um fato, uma habilidade, um conceito ou uma técnica. Compartilhe com o grupo para aprenderem juntos.'),
  ('Digital Detox Weekend',                  'Fim de Semana Sem Tela',                 'Dois dias completamente longe de telas desnecessárias.'),
  ('30-Day Read to My Kids Challenge',       'Desafio de 30 Dias: Ler para os Filhos', 'Leia para seus filhos todos os dias por 30 dias. Mesmo 10 minutos antes de dormir constroem um amor duradouro pela leitura — e dão um motivo para desacelerar juntos.'),
  ('Whole30',                                'Whole30',                                'Coma apenas alimentos naturais todos os dias.'),
  ('No Social Media for 30 Days',            '30 Dias Sem Redes Sociais',              'Fique fora das redes sociais todos os dias.'),
  ('Cold Shower Challenge',                  'Desafio do Banho Frio',                  'Tome um banho frio todos os dias.'),
  ('Language Practice',                      'Prática de Idiomas',                     'A prática diária leva à fluência. Mantenham-se no caminho, seja aprendendo inglês, japonês ou qualquer outro idioma.'),
  ('Alcohol-Free',                           'Sem Álcool',                             'Seja você curioso sobre sobriedade, em recuperação ou simplesmente cansado de beber — este é um grupo de responsabilidade de longo prazo, não um reset de 30 dias. Tranquilo, solidário, sem pressão.'),
  ('Nature Walks',                           'Caminhadas na Natureza',                 'Saia, respire fundo e registre. Um grupo de responsabilidade tranquilo para quem quer fazer do tempo na natureza um hábito regular.'),
  ('Weekend Workshop',                       'Oficina de Fim de Semana',               'Faça, conserte ou construa algo todo fim de semana. Até as pequenas conquistas contam — um remendo, uma demão de tinta, uma dobradiça apertada.'),
  ('Morning Workout',                        'Treino Matinal',                         'Exercite-se antes das 9h por 21 dias.'),
  ('The Weekly Stretch',                     'O Desafio Semanal',                      'Coragem pessoal como prática. Uma vez por semana, faça algo que te deixe desconfortável — uma conversa difícil, uma situação social nova, um risco criativo — e registre. O crescimento vive fora da zona de conforto.'),
  ('10K Steps for a Week',                   '10 Mil Passos por uma Semana',           'Um sprint rápido de 7 dias de movimento.'),
  ('Prayer Group',                           'Grupo de Oração',                        'Ore juntos com consistência. Registre seu tempo de silêncio diário e mantenham-se firmes.'),
  ('Read the New Testament in 260 Days',     'Leia o Novo Testamento em 260 Dias',     'Leia um capítulo por dia.'),
  ('Track Every Dollar',                     'Registre Cada Real',                     'Registre todas as despesas todos os dias.'),
  ('Running Club',                           'Clube de Corrida',                       'Registre suas corridas, compartilhe seus percursos e mantenham-se em movimento. Sem data de encerramento — apenas quilômetros consistentes.'),
  ('The Weekend Warrior',                    'O Guerreiro do Fim de Semana',           'Uma tarefa de manutenção da casa por semana. Consertar um vazamento, pintar um cômodo, fazer a revisão do carro — o que for o ser adulto neste fim de semana. Registre e mantenha a casa de pé juntos.'),
  ('Out There Doing It',                     'Mãos à Obra',                            'Sem projeto específico, sem meta específica. Apenas um grupo de pessoas que aparecem e provam que fizeram algo produtivo a cada dia. Registre. Qualquer coisa conta.'),
  ('Writers'' Room',                         'Sala dos Escritores',                    'Escreva com consistência ao lado de quem entende a luta. Registre sua contagem de palavras ou simplesmente que você apareceu.'),
  ('Staying Connected',                      'Mantendo Conexões',                      'Relacionamentos precisam de manutenção. Um grupo para quem quer tornar o contato regular com amigos e família um hábito inegociável — não apenas um desafio de 30 dias.'),
  ('10K Steps Daily',                        '10 Mil Passos por Dia',                  'Alcance 10.000 passos todos os dias por 30 dias.'),
  ('Inbox Zero for a Month',                 'Caixa de Entrada Zero por um Mês',       'Deixe sua caixa de entrada zerada todos os dias.'),
  ('Daily Steps',                            'Passos Diários',                         '10.000 passos é o desafio. Este é o estilo de vida. Um grupo permanente para quem registra seus passos todos os dias e quer outros fazendo o mesmo.'),
  ('Book Club',                              'Clube do Livro',                         'Leia junto, compartilhe pensamentos e mantenham-se no ritmo. Funciona para qualquer gênero ou velocidade de leitura.'),
  ('100 Pushups a Day',                      '100 Flexões por Dia',                    'Desenvolva sua consistência com 100 flexões por dia.'),
  ('30 Days of Kindness',                    '30 Dias de Bondade',                     'Realize um ato de bondade por dia.'),
  ('Skill Building',                         'Desenvolvimento de Habilidades',         'Escolha uma habilidade, pratique com consistência e registre suas sessões. Seja guitarra, marcenaria, xadrez ou aquarela — a prática deliberada precisa de responsabilidade.'),
  ('30-Day No Sugar Challenge',              'Desafio de 30 Dias Sem Açúcar',          'Evite açúcar adicionado todos os dias por 30 dias.'),
  ('Bird Watching',                          'Observação de Pássaros',                 'Registre seus avistamentos, acompanhe sua lista de vida e compartilhe a alegria dos pássaros com quem entende.'),
  ('Gratitude Journal',                      'Diário de Gratidão',                     'Três coisas, todos os dias. Uma prática simples que se acumula com o tempo. Responsabilidade contínua para quem passou do desafio para o hábito.'),
  ('Creative Practice',                      'Prática Criativa',                       'Crie algo — qualquer coisa — com consistência. Pintura, fotografia, música, cerâmica, design. O meio não importa; aparecer sim.'),
  ('DIY & Home Projects',                    'Projetos Caseiros DIY',                  'Mantenha o ritmo nos projetos da casa. Registre seu progresso, compartilhe o que está fazendo e tenha a responsabilidade para realmente terminar.'),
  ('No Spend Challenge',                     'Desafio Sem Gastos',                     'Sem compras desnecessárias a cada dia.'),
  ('Daily Coding',                           'Código Diário',                          '30 Dias de Código te coloca no hábito. Aqui é onde você mantém. Um grupo para desenvolvedores, aprendizes e criadores que querem escrever código todos os dias — indefinidamente.'),
  ('Save $5 a Day',                          'Economize R$5 por Dia',                  'Economize R$5 todos os dias.'),
  ('The Polymath Project',                   'O Projeto Polímata',                     'Um grupo para os genuinamente curiosos. A cada dia, aprenda uma coisa nova — qualquer coisa — e registre um breve resumo do que foi e por que importa. O objetivo é amplitude, não profundidade.'),
  ('Sleep Accountability',                   'Responsabilidade do Sono',               'O sono é a base de tudo. Registre suas horas, mantenham horários de dormir consistentes e sinta a diferença.'),
  ('50 Books in a Year',                     '50 Livros em um Ano',                    'Mantenha a leitura diária e o progresso semanal nos livros.'),
  ('Daily Hydration',                        'Hidratação Diária',                      'Oito copos parece simples. Fazer isso todos os dias sem responsabilidade é mais difícil do que parece. Um grupo descontraído para manter um ao outro bebendo água suficiente.'),
  ('Read the Bible in 400 days',             'Leia a Bíblia em 400 Dias',              'Leia três capítulos por dia.'),
  ('Healthy Eating',                         'Alimentação Saudável',                   'Não é uma dieta — é um hábito de longo prazo. Registre quando se alimentou bem, compartilhe o que está funcionando e mantenham-se no caminho sem a pressão do tudo ou nada.'),
  ('7 Hours of Sleep',                       '7 Horas de Sono',                        'Durma pelo menos 7 horas todas as noites.'),
  ('Plank Challenge',                        'Desafio da Prancha',                     'Pranchas diárias com progressão semanal.'),
  ('Family Fitness',                         'Fitness em Família',                     'Coloque toda a família em movimento. Registre passos, treinos ou qualquer atividade que estejam fazendo juntos.'),
  ('Strength Training',                      'Treino de Força',                        'Fique forte juntos. Registre suas sessões, acompanhe seus levantamentos e mantenham-se consistentes.'),
  ('Gym Buddies',                            'Parceiros de Academia',                  'Apareça, registre e se responsabilize mutuamente. O grupo que treina junto, fica junto.'),
  ('Phone Call a Day',                       'Uma Ligação por Dia',                    'Faça uma ligação por dia.'),
  ('Language Learning Sprint',               'Sprint de Aprendizado de Idioma',        'Estude um idioma por 15 minutos diariamente.'),
  ('Cook at Home',                           'Cozinhe em Casa',                        'Cozinhe em casa todos os dias.'),
  ('Savings Group',                          'Grupo de Poupança',                      'Poupe com consistência e se responsabilizem mutuamente. Registre suas contribuições e veja o hábito se fixar.'),
  ('Daily Journaling',                       'Diário Pessoal',                         'Escreva em seu diário todos os dias.'),
  ('Daily Scripture',                        'Escritura Diária',                       'Bíblia, Alcorão, Torá ou qualquer texto sagrado — um lar permanente para quem quer ler as escrituras com consistência e se responsabilizar mutuamente pela prática.'),
  ('Reach Out Daily',                        'Contato Diário',                         'Entre em contato com uma pessoa por dia.'),
  ('Budget Accountability',                  'Controle de Orçamento',                  'Registre cada centavo juntos. Anote quando revisou seu orçamento ou acompanhou seus gastos do dia. A consciência é o primeiro passo.')
) AS v(en_title, pt_title, pt_description)
JOIN group_templates t ON t.title = v.en_title
ON CONFLICT (template_id, language) DO NOTHING;

-- =============================================================
-- pt-BR goal translations
-- Lookup by (template English title, goal English title).
-- NULL description/log_title_prompt falls back to English master.
-- ON CONFLICT DO NOTHING silently skips removed templates/goals.
-- =============================================================

INSERT INTO group_template_goal_translations (goal_id, language, title, description, log_title_prompt)
SELECT g.id, 'pt-BR', v.pt_title, v.pt_description, v.pt_log_prompt
FROM (VALUES
  -- 30-Day Gratitude Journal
  ('30-Day Gratitude Journal',           'Gratitude entry',                          'Registro de gratidão',                        'Escreva sua lista de gratidão hoje.',                                    NULL::text),
  -- Screen Time
  ('Screen Time',                        'Stayed within screen time limit',          'Ficou dentro do limite de tempo de tela',     NULL,                                                                     NULL),
  -- 7-Day Launch Sprint
  ('7-Day Launch Sprint',                'Showed up today',                          'Apareceu hoje',                               NULL,                                                                     NULL),
  -- The 365-Day Marathon
  ('The 365-Day Marathon',               'Did the thing today',                      'Fez a tarefa hoje',                           NULL,                                                                     NULL),
  -- 30 Days of Coding
  ('30 Days of Coding',                  'Write code today',                         'Escrever código hoje',                        'Conclua pelo menos uma sessão de código hoje.',                          NULL),
  -- Morning Routines
  ('Morning Routines',                   'Morning routine done',                     'Rotina matinal concluída',                    NULL,                                                                     NULL),
  -- Wake Up at 5am
  ('Wake Up at 5am',                     'Wake by 5am',                              'Acordar às 5h',                               'Acorde às 5h.',                                                          NULL),
  -- Home Cooking (ongoing group)
  ('Home Cooking',                       'Cooked at home',                           'Cozinhou em casa',                            NULL,                                                                     NULL),
  -- 8 Glasses of Water
  ('8 Glasses of Water',                 '8 glasses of water',                       '8 copos de água',                             'Beba 8 copos de água hoje.',                                             NULL),
  -- Gardening Club (2 goals)
  ('Gardening Club',                     'Water the garden',                         'Regar o jardim',                              NULL,                                                                     NULL),
  ('Gardening Club',                     'Weekend garden session',                   'Sessão de jardim no fim de semana',            NULL,                                                                     NULL),
  -- Book a Month
  ('Book a Month',                       'Read 30 minutes',                          'Ler 30 minutos',                              'Leia por pelo menos 30 minutos.',                                        NULL),
  -- Couch to 5K
  ('Couch to 5K',                        'Run today',                                'Correr hoje',                                 'Conclua sua sessão de corrida do dia.',                                  NULL),
  -- No Alcohol for 30 Days
  ('No Alcohol for 30 Days',             'No alcohol today',                         'Sem álcool hoje',                             'Evite álcool durante o dia.',                                            NULL),
  -- Daily Discovery
  ('Daily Discovery',                    'Daily Discovery',                          'Descoberta Diária',                           'Compartilhe o que você aprendeu hoje',                                   NULL),
  -- Digital Detox Weekend
  ('Digital Detox Weekend',              'Stay off screens',                         'Ficar longe das telas',                       'Fique longe de telas não essenciais hoje.',                              NULL),
  -- 30-Day Read to My Kids Challenge
  ('30-Day Read to My Kids Challenge',   'Read to my kids',                          'Ler para meus filhos',                        'Leia em voz alta para seus filhos por pelo menos 10 minutos',            NULL),
  -- Whole30
  ('Whole30',                            'Whole foods only',                         'Apenas alimentos naturais',                   'Coma apenas alimentos naturais hoje.',                                   NULL),
  -- No Social Media for 30 Days
  ('No Social Media for 30 Days',        'No social media today',                    'Sem redes sociais hoje',                      'Evite as redes sociais durante o dia.',                                  NULL),
  -- Cold Shower Challenge
  ('Cold Shower Challenge',              'Cold shower',                              'Banho frio',                                  'Tome um banho frio hoje.',                                               NULL),
  -- Language Practice (2 goals)
  ('Language Practice',                  'Study session',                            'Sessão de estudo',                            NULL,                                                                     NULL),
  ('Language Practice',                  'Practice today',                           'Praticar hoje',                               NULL,                                                                     NULL),
  -- Alcohol-Free
  ('Alcohol-Free',                       'Alcohol-free today',                       'Sem álcool hoje',                             NULL,                                                                     NULL),
  -- Nature Walks (2 goals)
  ('Nature Walks',                       'Walk outside today',                       'Caminhar ao ar livre hoje',                   NULL,                                                                     NULL),
  ('Nature Walks',                       'Time outdoors',                            'Tempo ao ar livre',                           NULL,                                                                     NULL),
  -- Weekend Workshop
  ('Weekend Workshop',                   'Weekend Project',                          'Projeto do Fim de Semana',                    'Compartilhe o que você fez, consertou ou construiu neste fim de semana', NULL),
  -- Morning Workout
  ('Morning Workout',                    'Workout before 9am',                       'Treino antes das 9h',                         'Conclua um treino antes das 9h.',                                        NULL),
  -- The Weekly Stretch
  ('The Weekly Stretch',                 'Did something uncomfortable this week',    'Fez algo desconfortável esta semana',          NULL,                                                                     NULL),
  -- 10K Steps for a Week
  ('10K Steps for a Week',               '10,000 steps',                             '10.000 passos',                               'Alcance 10.000 passos hoje.',                                            NULL),
  -- Prayer Group
  ('Prayer Group',                       'Morning prayer / quiet time',              'Oração matinal / tempo de silêncio',          NULL,                                                                     NULL),
  -- Read the New Testament in 260 Days
  ('Read the New Testament in 260 Days', 'Read 1 chapter',                           'Ler 1 capítulo',                              'Leia 1 capítulo hoje.',                                                  NULL),
  -- Track Every Dollar
  ('Track Every Dollar',                 'Tracked all expenses',                     'Registrou todas as despesas',                 'Registre todas as despesas hoje.',                                       NULL),
  -- Running Club (2 goals)
  ('Running Club',                       'Run today',                                'Correr hoje',                                 NULL,                                                                     NULL),
  ('Running Club',                       'Weekly distance',                          'Distância semanal',                           NULL,                                                                     NULL),
  -- The Weekend Warrior
  ('The Weekend Warrior',                'Completed a home task this week',          'Concluiu uma tarefa doméstica esta semana',   NULL,                                                                     NULL),
  -- Out There Doing It
  ('Out There Doing It',                 'Got something done today',                 'Fez algo produtivo hoje',                     NULL,                                                                     NULL),
  -- Writers' Room (2 goals)
  ('Writers'' Room',                     'Write today',                              'Escrever hoje',                               NULL,                                                                     NULL),
  ('Writers'' Room',                     'Word count',                               'Contagem de palavras',                        NULL,                                                                     NULL),
  -- Staying Connected
  ('Staying Connected',                  'Reached out to someone today',             'Entrou em contato com alguém hoje',           NULL,                                                                     NULL),
  -- 10K Steps Daily
  ('10K Steps Daily',                    '10,000 steps',                             '10.000 passos',                               'Caminhe pelo menos 10.000 passos hoje.',                                 NULL),
  -- Inbox Zero for a Month
  ('Inbox Zero for a Month',             'Inbox cleared',                            'Caixa de entrada zerada',                     'Chegue a zero na caixa de entrada hoje.',                                NULL),
  -- Daily Steps (2 goals)
  ('Daily Steps',                        'Daily steps',                              'Passos diários',                              NULL,                                                                     NULL),
  ('Daily Steps',                        'Step goal reached',                        'Meta de passos alcançada',                    NULL,                                                                     NULL),
  -- Book Club (2 goals)
  ('Book Club',                          'Read today',                               'Ler hoje',                                    NULL,                                                                     NULL),
  ('Book Club',                          'Pages read',                               'Páginas lidas',                               NULL,                                                                     NULL),
  -- 100 Pushups a Day
  ('100 Pushups a Day',                  '100 pushups',                              '100 flexões',                                 'Complete 100 flexões hoje.',                                             NULL),
  -- 30 Days of Kindness
  ('30 Days of Kindness',                'One kind act',                             'Um ato de bondade',                           'Faça um ato de bondade intencional.',                                    NULL),
  -- Skill Building (2 goals)
  ('Skill Building',                     'Practice session',                         'Sessão de prática',                           NULL,                                                                     NULL),
  ('Skill Building',                     'Practised today',                          'Praticou hoje',                               NULL,                                                                     NULL),
  -- 30-Day No Sugar Challenge
  ('30-Day No Sugar Challenge',          'No added sugar today',                     'Sem açúcar adicionado hoje',                  'Evite todo açúcar adicionado hoje.',                                     NULL),
  -- Bird Watching (2 goals)
  ('Bird Watching',                      'Time outside birdwatching',                'Tempo ao ar livre observando pássaros',        NULL,                                                                     NULL),
  ('Bird Watching',                      'Spot a new species',                       'Avistar uma nova espécie',                    NULL,                                                                     NULL),
  -- Gratitude Journal (ongoing)
  ('Gratitude Journal',                  'Wrote gratitude today',                    'Escreveu gratidão hoje',                      NULL,                                                                     NULL),
  -- Creative Practice
  ('Creative Practice',                  'Created something today',                  'Criou algo hoje',                             NULL,                                                                     NULL),
  -- DIY & Home Projects
  ('DIY & Home Projects',                'Worked on a project',                      'Trabalhou em um projeto',                     NULL,                                                                     NULL),
  -- No Spend Challenge
  ('No Spend Challenge',                 'No unnecessary purchases',                 'Sem compras desnecessárias',                  'Evite gastos não essenciais hoje.',                                      NULL),
  -- Daily Coding
  ('Daily Coding',                       'Wrote code today',                         'Escreveu código hoje',                        NULL,                                                                     NULL),
  -- Save $5 a Day
  ('Save $5 a Day',                      'Saved $5',                                 'Economizou R$5',                              'Economize pelo menos R$5 hoje.',                                         NULL),
  -- The Polymath Project
  ('The Polymath Project',               'Learned something new today',              'Aprendeu algo novo hoje',                     NULL,                                                                     NULL),
  -- Sleep Accountability (2 goals)
  ('Sleep Accountability',               'Hours slept',                              'Horas dormidas',                              NULL,                                                                     NULL),
  ('Sleep Accountability',               'In bed by target time',                    'Na cama no horário planejado',                NULL,                                                                     NULL),
  -- 50 Books in a Year (2 goals)
  ('50 Books in a Year',                 'Finish 1 book',                            'Terminar 1 livro',                            'Conclua um livro esta semana.',                                          NULL),
  ('50 Books in a Year',                 'Read today',                               'Ler hoje',                                    'Faça sua sessão de leitura diária.',                                     NULL),
  -- Daily Hydration (2 goals)
  ('Daily Hydration',                    'Glasses of water',                         'Copos de água',                               NULL,                                                                     NULL),
  ('Daily Hydration',                    'Drank enough water today',                 'Bebeu água suficiente hoje',                  NULL,                                                                     NULL),
  -- Read the Bible in 400 days
  ('Read the Bible in 400 days',         'Read 3 chapters',                          'Ler 3 capítulos',                             'Leia 3 capítulos hoje.',                                                 NULL),
  -- Healthy Eating
  ('Healthy Eating',                     'Ate well today',                           'Comeu bem hoje',                              NULL,                                                                     NULL),
  -- 7 Hours of Sleep
  ('7 Hours of Sleep',                   '7+ hours sleep',                           '7+ horas de sono',                            'Durma pelo menos 7 horas.',                                              NULL),
  -- Plank Challenge
  ('Plank Challenge',                    'Plank hold',                               'Prancha',                                     'Faça uma prancha hoje.',                                                 NULL),
  -- Family Fitness
  ('Family Fitness',                     'Active for 30 minutes',                    'Ativo por 30 minutos',                        NULL,                                                                     NULL),
  -- Strength Training
  ('Strength Training',                  'Strength session done',                    'Sessão de força concluída',                   NULL,                                                                     NULL),
  -- Gym Buddies
  ('Gym Buddies',                        'Hit the gym',                              'Foi à academia',                              NULL,                                                                     NULL),
  -- Phone Call a Day
  ('Phone Call a Day',                   'Made a phone call',                        'Fez uma ligação',                             'Ligue para pelo menos uma pessoa.',                                      NULL),
  -- Language Learning Sprint
  ('Language Learning Sprint',           'Study 15 minutes',                         'Estudar 15 minutos',                          'Pratique seu idioma por 15 minutos.',                                    NULL),
  -- Cook at Home (challenge)
  ('Cook at Home',                       'Cooked at home',                           'Cozinhou em casa',                            'Cozinhe em casa hoje.',                                                  NULL),
  -- Savings Group
  ('Savings Group',                      'Saved today',                              'Poupou hoje',                                 NULL,                                                                     NULL),
  -- Daily Journaling
  ('Daily Journaling',                   'Journal entry',                            'Registro no diário',                          'Escreva um registro no diário hoje.',                                    NULL),
  -- Daily Scripture
  ('Daily Scripture',                    'Read scripture today',                     'Leu a escritura hoje',                        NULL,                                                                     NULL),
  -- Reach Out Daily
  ('Reach Out Daily',                    'Reached out to someone',                   'Entrou em contato com alguém',                'Entre em contato com um amigo ou familiar.',                             NULL),
  -- Budget Accountability
  ('Budget Accountability',              'Tracked spending today',                   'Registrou os gastos hoje',                    NULL,                                                                     NULL)
) AS v(en_template_title, en_goal_title, pt_title, pt_description, pt_log_prompt)
JOIN group_templates t ON t.title = v.en_template_title
JOIN group_template_goals g ON g.template_id = t.id AND g.title = v.en_goal_title
ON CONFLICT (goal_id, language) DO NOTHING;
