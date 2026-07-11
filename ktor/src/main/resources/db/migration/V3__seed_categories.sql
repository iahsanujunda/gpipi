insert into budget_envelope (name, period, amount) values
    ('Weekly Food',        'WEEKLY',  15000),
    ('Monthly Groceries',  'MONTHLY', 60000),
    ('Monthly Transport',  'MONTHLY', 20000),
    ('Monthly Household',  'MONTHLY', 20000),
    ('Monthly Other',      'MONTHLY', 10000);

insert into category (envelope_id, name, description) values
    ((select id from budget_envelope where name = 'Weekly Food'),
     'Eating Out',
     'restaurants, cafes, ramen, izakaya, takeout meals'),

    ((select id from budget_envelope where name = 'Weekly Food'),
     'Convenience Store',
     'konbini, small quick purchases (Seven, Lawson, FamilyMart)'),

    ((select id from budget_envelope where name = 'Monthly Groceries'),
     'Monthly Groceries',
     'supermarket runs, bulk shopping (Ito Yokado, Tokyu Store, OK)'),

    ((select id from budget_envelope where name = 'Monthly Transport'),
     'Transport',
     'trains, buses, taxi, IC top-ups'),

    ((select id from budget_envelope where name = 'Monthly Household'),
     'Household',
     'daily goods, drugstore, home supplies'),

    ((select id from budget_envelope where name = 'Monthly Other'),
     'Other',
     'anything that fits nothing above');
