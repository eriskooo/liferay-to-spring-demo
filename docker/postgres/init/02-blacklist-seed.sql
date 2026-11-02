-- Známy stav pre testy: rovnaké dáta ako InMemoryBlacklistService.seed(). RESTART IDENTITY = deterministické ID
-- (záznamy 1-8, identifikátory 1-10), na ktoré sa spoliehajú unit, smoke aj e2e testy. Spúšťa sa aj ako reset.
TRUNCATE blacklist.blacklist_note, blacklist.blacklist_identifier, blacklist.blacklist_record RESTART IDENTITY CASCADE;

INSERT INTO blacklist.blacklist_record
    (first_name, last_name, birth_date, state, state_reason, condition_type, valid_from, valid_to)
VALUES ('Petr', 'Vzorka', '1980-05-12', 'ACTIVE', 'FRAUD', 'BLOCK_ALL', '2024-01-10', NULL),
       ('Věra', 'Příkladová', '1975-11-03', 'ACTIVE', 'DEBT', 'REQUIRE_DEPOSIT', '2023-06-01', '2027-06-01'),
       ('Jan', 'Testovací', '1990-02-20', 'INACTIVE', 'DEBT', 'MANUAL_APPROVAL', '2022-03-15', '2024-03-15'),
       ('Eva', 'Ukázková', '1988-07-30', 'ACTIVE', 'ABUSE', 'MANUAL_APPROVAL', '2025-02-01', NULL),
       ('Tomáš', 'Fiktivní', '1969-12-24', 'ACTIVE', 'FRAUD', 'BLOCK_ALL', '2021-09-09', NULL),
       ('Lucie', 'Demová', '1995-04-18', 'INACTIVE', 'ABUSE', 'BLOCK_ALL', '2020-01-01', '2021-01-01'),
       ('Karel', 'Modelový', '1983-08-08', 'ACTIVE', 'DEBT', 'REQUIRE_DEPOSIT', '2025-10-01', NULL),
       ('Petr', 'Vzorkový', '1979-01-01', 'ACTIVE', 'DEBT', 'MANUAL_APPROVAL', '2024-05-05', NULL);

INSERT INTO blacklist.blacklist_identifier (record_id, type, value)
VALUES (1, 'BIRTH_CODE', '800512/1234'),
       (1, 'ID_CARD', 'AB123456'),
       (2, 'BIRTH_CODE', '755103/2345'),
       (3, 'ID_CARD', 'CD987654'),
       (4, 'PASSPORT', 'P1234567'),
       (5, 'BIRTH_CODE', '691224/3456'),
       (5, 'PASSPORT', 'X7654321'),
       (6, 'ID_CARD', 'EF555444'),
       (7, 'COMPANY_REG_NO', '12345678'),
       (8, 'BIRTH_CODE', '790101/4567');

INSERT INTO blacklist.blacklist_note (record_id, text, author, created_at)
VALUES (1, 'Opakované pokusy o objednávku na cizí doklady.', 'Systém', '2024-01-10 09:00');
